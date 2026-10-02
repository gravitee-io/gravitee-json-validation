/*
 * Copyright © 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.json.validation;

import static io.gravitee.json.validation.helper.JsonHelper.clearNullValues;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.networknt.schema.Error;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Draft7;
import com.networknt.schema.format.Format;
import com.networknt.schema.keyword.AnnotationKeyword;
import com.networknt.schema.path.PathType;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class JsonSchemaValidatorImpl implements JsonSchemaValidator {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
        .build();
    private static final long MAX_CACHE_SIZE = 1000L;

    /**
     * Keyed on the parsed schema node, not the raw string: JsonNode uses content-based equals/hashCode
     * (ObjectNode compares children as a map), so schemas that differ only in whitespace or key order
     * share one entry. The key node MUST stay read-only after insertion — mutating it would change its
     * hashCode and corrupt the cache.
     */
    private static final Cache<JsonNode, Schema> SCHEMA_CACHE = Caffeine.newBuilder().maximumSize(MAX_CACHE_SIZE).build();

    /**
     * Default networknt regex format validates against ECMA-262 syntax; existing schemas use Java Pattern syntax.
     * Override registers JavaRegexFormat for both regex and java-regex names so schema patterns compile via
     * java.util.regex.Pattern instead of being rejected.
     *
     * Legacy Gravitee schemas carry the Draft 4 "id" keyword (renamed to "$id" in Draft 6+). Draft 7 has no
     * validator registered for bare "id", so SchemaRegistry throws "No suitable validator for id" on those
     * documents. Registering it as an AnnotationKeyword makes it a no-op so the schemas compile.
     */
    private static final Dialect DRAFT7_WITH_REGEX = Dialect.builder(Draft7.getInstance())
        .format(new JavaRegexFormat("java-regex"))
        .format(new JavaRegexFormat("regex"))
        .keyword(new AnnotationKeyword("id"))
        .build();

    /**
     * Factory that compiles JSON schema strings into Schema objects. Built once because:
     *   - Binds the custom dialect above so all schemas inherit the Java-regex override.
     *   - formatAssertionsEnabled(true) — Draft 7 treats formats as annotations by default; flip to assertions so
     *     regex/format violations actually fail validation.
     *   - pathType(PathType.LEGACY) — emits $.foo.bar style paths (matches old everit output, keeps findObject happy).
     */
    private static final SchemaRegistry SCHEMA_REGISTRY = SchemaRegistry.withDialect(DRAFT7_WITH_REGEX, builder ->
        builder.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).pathType(PathType.LEGACY).build())
    );

    @Override
    public String validate(String schema, String json) {
        String safeConfiguration = clearNullValues(MAPPER, json);

        if (schema != null && !schema.isEmpty()) {
            try {
                JsonNode jsonNode = MAPPER.readTree(safeConfiguration);
                JsonNode schemaNode = MAPPER.readTree(schema);
                Schema schemaValidator = getSchemaValidator(schemaNode);

                List<Error> errors = schemaValidator.validate(jsonNode);
                boolean corrected = !errors.isEmpty();
                if (corrected) {
                    applyCorrections(jsonNode, schemaNode, errors);
                }

                injectOptionalDefaults(jsonNode, schemaNode, schemaNode);
                boolean pruned = pruneInactiveVariantProperties(jsonNode, schemaNode, schemaNode);

                // Corrections are best-effort: the corrected document is validated again and only accepted when it
                // fully conforms. This is what prevents accepting a oneOf that is invalid for every branch.
                if (corrected || pruned) {
                    List<Error> remainingErrors = withoutAmbiguousOneOfs(schemaValidator.validate(jsonNode));
                    if (!remainingErrors.isEmpty()) {
                        throw new InvalidJsonException(describe(jsonNode, schemaNode, remainingErrors));
                    }
                }

                return MAPPER.writeValueAsString(jsonNode);
            } catch (JsonProcessingException e) {
                throw new InvalidJsonException(e.getMessage(), e);
            }
        }
        return safeConfiguration;
    }

    private Schema getSchemaValidator(JsonNode schemaNode) {
        return SCHEMA_CACHE.get(schemaNode, SCHEMA_REGISTRY::getSchema);
    }

    /**
     * Recovery mutations: default injection for missing required properties and additionalProperties pruning.
     * For each failing oneOf a single branch is selected first (see {@link #selectBranch}); only the errors raised
     * by that branch are acted upon, so the errors of the other branches can neither remove a field nor inject a
     * default into the selected one. Nothing is rejected here: the caller re-validates the result.
     */
    private void applyCorrections(JsonNode jsonNode, JsonNode schemaRoot, List<Error> errors) {
        OneOfSelections selections = selectOneOfBranches(jsonNode, schemaRoot, errors, true);

        for (Error error : errors) {
            if (!selections.belongsToSelectedBranches(error, false)) continue;

            switch (error.getKeyword()) {
                case "required" -> handleRequiredError(jsonNode, schemaRoot, error);
                case "additionalProperties" -> handleAdditionalPropertiesError(jsonNode, error);
                default -> {}
            }
        }
    }

    /**
     * A oneOf satisfied by several branches is tolerated, as it always has been: the configuration conforms to at least
     * one variant, and unions whose branches overlap (no discriminator, no additionalProperties: false) would otherwise
     * reject configurations accepted so far. The oneOf error is dropped together with the errors of its failing branches.
     */
    private List<Error> withoutAmbiguousOneOfs(List<Error> errors) {
        List<String> ambiguousOneOfs = errors
            .stream()
            .filter(error -> "oneOf".equals(error.getKeyword()) && "oneOf.indexes".equals(error.getMessageKey()))
            .map(error -> error.getEvaluationPath().toString())
            .toList();
        if (ambiguousOneOfs.isEmpty()) return errors;

        return errors
            .stream()
            .filter(error -> {
                String path = error.getEvaluationPath().toString();
                return ambiguousOneOfs.stream().noneMatch(oneOf -> path.equals(oneOf) || path.startsWith(oneOf + "["));
            })
            .toList();
    }

    /**
     * Builds the rejection message. A failing oneOf whose branch can be identified (by its discriminator, or
     * because it is the only one accepting the fields present) is reported through that branch's own errors
     * instead of the errors of every branch.
     */
    private String describe(JsonNode jsonNode, JsonNode schemaRoot, List<Error> errors) {
        OneOfSelections selections = selectOneOfBranches(jsonNode, schemaRoot, errors, false);

        Set<String> messages = new LinkedHashSet<>();
        for (Error error : errors) {
            if (selections.isReported(error, errors)) {
                messages.add(error.toString());
            }
        }
        return String.join("\n", messages);
    }

    private void handleRequiredError(JsonNode jsonNode, JsonNode schemaRoot, Error error) {
        Object[] arguments = error.getArguments();
        if (arguments == null || arguments.length == 0) {
            return;
        }
        String missingField = arguments[0].toString();
        ObjectNode target = findObject(jsonNode, error.getInstanceLocation().toString());
        if (target == null || target.has(missingField)) {
            return;
        }

        JsonNode ownerSchema = requiredOwnerSchema(schemaRoot, error.getSchemaLocation());
        if (ownerSchema == null) {
            return;
        }
        JsonNode properties = ownerSchema.get("properties");
        JsonNode propertySchema = properties != null ? resolveRef(schemaRoot, properties.get(missingField)) : null;

        if (propertySchema != null && propertySchema.has("default")) {
            JsonNode defaultValue = propertySchema.get("default");
            if (defaultFitsPresentFields(target, schemaRoot, ownerSchema, missingField, defaultValue)) {
                target.set(missingField, defaultValue.deepCopy());
            }
        }
    }

    private void handleAdditionalPropertiesError(JsonNode jsonNode, Error error) {
        Object[] arguments = error.getArguments();
        if (arguments == null || arguments.length == 0) {
            return;
        }
        ObjectNode target = findObject(jsonNode, error.getInstanceLocation().toString());
        if (target != null) {
            target.remove(arguments[0].toString());
        }
    }

    // -------------------------------------------------------------------------
    // oneOf branch selection
    // -------------------------------------------------------------------------

    private enum SelectionMode {
        /** The input carries a discriminator value matching the branch. */
        MATCHED,
        /** The discriminator is absent; its default designates the branch, which accepts every field present. */
        DEFAULTED,
        /** No discriminator to rely on: first branch the input does not contradict (legacy behavior). */
        INFERRED,
        /**
         * The discriminator default designates a branch that rejects fields already present. Nothing is injected (the
         * default would silently switch variant and drop those fields); the branch only serves to report errors.
         */
        REPORT_ONLY,
    }

    private record BranchSelection(int index, SelectionMode mode, Map<String, JsonNode> discriminatorDefaults, boolean unambiguous) {}

    private record OneOfFailure(String evaluationPath, int selectedBranch, boolean unambiguous) {}

    /** Failing oneOfs and the branch selected for each, used to tell apart the errors of selected and discarded branches. */
    private record OneOfSelections(List<OneOfFailure> failures) {
        /**
         * Whether {@code error} is not nested in a discarded branch of any failing oneOf. With {@code unambiguousOnly},
         * errors nested in a oneOf whose branch could not be identified unambiguously are discarded as well.
         */
        boolean belongsToSelectedBranches(Error error, boolean unambiguousOnly) {
            for (OneOfFailure failure : failures) {
                int branch = branchIndex(error, failure.evaluationPath());
                if (branch >= 0 && (branch != failure.selectedBranch() || (unambiguousOnly && !failure.unambiguous()))) {
                    return false;
                }
            }
            return true;
        }

        boolean isReported(Error error, List<Error> errors) {
            if (!belongsToSelectedBranches(error, true)) return false;
            if (!"oneOf".equals(error.getKeyword())) return true;

            String evaluationPath = error.getEvaluationPath().toString();
            OneOfFailure failure = failures
                .stream()
                .filter(f -> f.evaluationPath().equals(evaluationPath))
                .findFirst()
                .orElse(null);
            if (failure == null || !failure.unambiguous()) return true;

            // The selected branch's own errors replace the generic "must be valid to one and only one schema". When
            // the branch has none (it is valid, but so is another one), the oneOf error is the only explanation.
            return errors
                .stream()
                .noneMatch(e -> branchIndex(e, evaluationPath) == failure.selectedBranch() && belongsToSelectedBranches(e, true));
        }

        /** Index of the oneOf branch {@code error} was raised in, or -1 when it is not nested under that oneOf. */
        static int branchIndex(Error error, String oneOfEvaluationPath) {
            String path = error.getEvaluationPath().toString();
            String prefix = oneOfEvaluationPath + "[";
            if (!path.startsWith(prefix)) return -1;
            int end = path.indexOf(']', prefix.length());
            if (end < 0) return -1;
            try {
                return Integer.parseInt(path.substring(prefix.length(), end));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    private OneOfSelections selectOneOfBranches(JsonNode jsonNode, JsonNode schemaRoot, List<Error> errors, boolean applyCorrections) {
        OneOfSelections selections = new OneOfSelections(new ArrayList<>());

        // Outermost first, so that a oneOf nested in a discarded branch is skipped.
        List<Error> oneOfErrors = errors
            .stream()
            .filter(error -> "oneOf".equals(error.getKeyword()))
            .sorted(Comparator.comparingInt(error -> error.getEvaluationPath().toString().length()))
            .toList();

        for (Error error : oneOfErrors) {
            if (selections.belongsToSelectedBranches(error, false)) {
                selections.failures().add(selectOneOfBranch(jsonNode, schemaRoot, error, errors, applyCorrections));
            }
        }
        return selections;
    }

    private OneOfFailure selectOneOfBranch(
        JsonNode jsonNode,
        JsonNode schemaRoot,
        Error oneOfError,
        List<Error> errors,
        boolean applyCorrections
    ) {
        String evaluationPath = oneOfError.getEvaluationPath().toString();
        ObjectNode target = findObject(jsonNode, oneOfError.getInstanceLocation().toString());
        JsonNode container = oneOfContainer(schemaRoot, oneOfError.getSchemaLocation());
        if (target == null || container == null) {
            return new OneOfFailure(evaluationPath, -1, false);
        }

        List<JsonNode> branches = new ArrayList<>();
        for (JsonNode branch : container.get("oneOf")) {
            JsonNode resolved = resolveRef(schemaRoot, branch);
            // Branches without "properties" cannot be matched against the input's fields.
            branches.add(resolved != null && resolved.has("properties") ? resolved : null);
        }

        int[] errorCounts = new int[branches.size()];
        for (Error error : errors) {
            int branch = OneOfSelections.branchIndex(error, evaluationPath);
            if (branch >= 0 && branch < errorCounts.length) errorCounts[branch]++;
        }

        BranchSelection selection = selectBranch(schemaRoot, container, branches, target, errorCounts);
        if (selection == null) {
            return new OneOfFailure(evaluationPath, -1, false);
        }

        if (applyCorrections) {
            if (selection.mode() == SelectionMode.REPORT_ONLY) {
                // Discard every branch so that none of their errors trigger a correction.
                return new OneOfFailure(evaluationPath, -1, false);
            }
            applySelectedBranch(schemaRoot, container, branches.get(selection.index()), target, selection);
        }
        return new OneOfFailure(evaluationPath, selection.index(), selection.unambiguous());
    }

    /**
     * Deterministic branch selection, by decreasing priority:
     * <ol>
     *   <li>a branch whose const (or single-value enum) discriminator equals the value supplied by the input;</li>
     *   <li>when the discriminator is absent, the branch designated by its default value, provided that branch accepts
     *       every field present. Otherwise the default is not applied (it would switch the variant and drop fields)
     *       and the branch accepting the fields present is used to report the errors;</li>
     *   <li>without discriminator, the branch the input does not contradict declaring most of the fields present.</li>
     * </ol>
     * When several branches qualify for 1 or 2, the one with the fewest validation errors wins. Remaining ties go to the
     * first branch, but the selection is then flagged ambiguous and the generic oneOf error is reported.
     *
     * @return the selection, or {@code null} when the input contradicts every branch.
     */
    private BranchSelection selectBranch(
        JsonNode schemaRoot,
        JsonNode container,
        List<JsonNode> branches,
        ObjectNode target,
        int[] errorCounts
    ) {
        List<Map<String, List<JsonNode>>> constraints = branches
            .stream()
            .map(branch -> branch == null ? Map.<String, List<JsonNode>>of() : valueConstraints(schemaRoot, branch))
            .toList();

        List<Integer> matching = branchesMatchingDiscriminator(branches, constraints, target);
        if (!matching.isEmpty()) {
            return closestBranch(matching, errorCounts, SelectionMode.MATCHED, Map.of());
        }

        Map<String, JsonNode> defaults = discriminatorDefaults(schemaRoot, container, branches, constraints, target);
        if (!defaults.isEmpty()) {
            ObjectNode withDefaults = target.deepCopy();
            defaults.forEach((name, value) -> withDefaults.set(name, value.deepCopy()));

            List<Integer> defaulted = branchesMatchingDiscriminator(branches, constraints, withDefaults);
            if (!defaulted.isEmpty()) {
                BranchSelection selection = closestBranch(defaulted, errorCounts, SelectionMode.DEFAULTED, defaults);
                if (allowedProperties(branches.get(selection.index())).allMatch(target)) {
                    return selection;
                }
                List<Integer> accepting = branchesAcceptingFields(branches, constraints, target);
                return accepting.isEmpty()
                    ? new BranchSelection(selection.index(), SelectionMode.REPORT_ONLY, Map.of(), selection.unambiguous())
                    : closestBranch(accepting, errorCounts, SelectionMode.REPORT_ONLY, Map.of());
            }
        }

        List<Integer> candidates = branchesAcceptingFields(branches, constraints, target);
        if (candidates.isEmpty()) {
            candidates = branchesNotContradicted(branches, constraints, target);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // Prefer the branch declaring most of the fields present: a branch merely tolerating them (no
        // additionalProperties: false) must not win over the one they were written for.
        int[] undeclaredFields = new int[branches.size()];
        for (int candidate : candidates) {
            undeclaredFields[candidate] = -declaredFieldCount(branches.get(candidate), target);
        }
        return closestBranch(candidates, undeclaredFields, SelectionMode.INFERRED, Map.of());
    }

    /** The candidate with the lowest score, the first one on ties; ambiguous when the lowest score is shared. */
    private BranchSelection closestBranch(
        List<Integer> candidates,
        int[] scores,
        SelectionMode mode,
        Map<String, JsonNode> discriminatorDefaults
    ) {
        int best = candidates.get(0);
        boolean unambiguous = true;
        for (int candidate : candidates.subList(1, candidates.size())) {
            if (scores[candidate] < scores[best]) {
                best = candidate;
                unambiguous = true;
            } else if (scores[candidate] == scores[best]) {
                unambiguous = false;
            }
        }
        return new BranchSelection(best, mode, discriminatorDefaults, unambiguous);
    }

    private int declaredFieldCount(JsonNode branch, ObjectNode object) {
        JsonNode properties = branch.get("properties");
        int count = 0;
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            if (properties.has(names.next())) count++;
        }
        return count;
    }

    private List<Integer> branchesMatchingDiscriminator(
        List<JsonNode> branches,
        List<Map<String, List<JsonNode>>> constraints,
        ObjectNode object
    ) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) {
            if (branches.get(i) != null && discriminatorMatches(constraints.get(i), object) > 0) result.add(i);
        }
        return result;
    }

    private List<Integer> branchesNotContradicted(
        List<JsonNode> branches,
        List<Map<String, List<JsonNode>>> constraints,
        ObjectNode object
    ) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) {
            if (branches.get(i) != null && discriminatorMatches(constraints.get(i), object) >= 0) result.add(i);
        }
        return result;
    }

    private List<Integer> branchesAcceptingFields(
        List<JsonNode> branches,
        List<Map<String, List<JsonNode>>> constraints,
        ObjectNode object
    ) {
        List<Integer> result = new ArrayList<>();
        for (int i : branchesNotContradicted(branches, constraints, object)) {
            if (allowedProperties(branches.get(i)).allMatch(object)) result.add(i);
        }
        return result;
    }

    /**
     * -1 when {@code object} contradicts one of the discriminators (const or single-value enum), else the number of
     * discriminators it matches. Multi-value enums do not identify a branch: violating one is an ordinary validation
     * error of the branch, not a reason to discard it.
     */
    private int discriminatorMatches(Map<String, List<JsonNode>> constraints, ObjectNode object) {
        int matches = 0;
        for (Map.Entry<String, List<JsonNode>> constraint : constraints.entrySet()) {
            JsonNode value = object.get(constraint.getKey());
            if (value == null || constraint.getValue().size() != 1) continue;
            if (!constraint.getValue().contains(value)) return -1;
            matches++;
        }
        return matches;
    }

    /** Values allowed by the const or enum of each property of {@code schema}. */
    private Map<String, List<JsonNode>> valueConstraints(JsonNode schemaRoot, JsonNode schema) {
        Map<String, List<JsonNode>> constraints = new LinkedHashMap<>();
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject()) return constraints;

        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            JsonNode propertySchema = resolveRef(schemaRoot, entry.getValue());
            if (propertySchema == null) continue;
            if (propertySchema.has("const")) {
                constraints.put(entry.getKey(), List.of(propertySchema.get("const")));
            } else if (propertySchema.has("enum") && propertySchema.get("enum").isArray()) {
                List<JsonNode> values = new ArrayList<>();
                propertySchema.get("enum").forEach(values::add);
                constraints.put(entry.getKey(), values);
            }
        }
        return constraints;
    }

    /**
     * Defaults of the discriminators absent from {@code target}, read from the schema holding the oneOf first, then
     * from the branches (e.g. {@code "type": { "const": "A", "default": "A" }}).
     */
    private Map<String, JsonNode> discriminatorDefaults(
        JsonNode schemaRoot,
        JsonNode container,
        List<JsonNode> branches,
        List<Map<String, List<JsonNode>>> constraints,
        ObjectNode target
    ) {
        Set<String> discriminators = new LinkedHashSet<>();
        for (Map<String, List<JsonNode>> branchConstraints : constraints) {
            branchConstraints.forEach((name, values) -> {
                if (values.size() == 1) discriminators.add(name);
            });
        }

        Map<String, JsonNode> defaults = new LinkedHashMap<>();
        for (String discriminator : discriminators) {
            if (target.has(discriminator)) continue;

            List<JsonNode> sources = new ArrayList<>();
            sources.add(container);
            sources.addAll(branches);
            for (JsonNode source : sources) {
                JsonNode properties = source != null ? source.get("properties") : null;
                JsonNode propertySchema = properties != null ? resolveRef(schemaRoot, properties.get(discriminator)) : null;
                if (propertySchema != null && propertySchema.has("default")) {
                    defaults.put(discriminator, propertySchema.get("default"));
                    break;
                }
            }
        }
        return defaults;
    }

    private void applySelectedBranch(
        JsonNode schemaRoot,
        JsonNode container,
        JsonNode branch,
        ObjectNode target,
        BranchSelection selection
    ) {
        AllowedProperties allowed = allowedProperties(branch);

        // Defaults of the schema holding the oneOf, minus those the selected branch does not allow.
        Set<String> existing = new HashSet<>();
        target.fieldNames().forEachRemaining(existing::add);
        injectOptionalDefaults(target, schemaRoot, container);
        List<String> injected = new ArrayList<>();
        target
            .fieldNames()
            .forEachRemaining(name -> {
                if (!existing.contains(name) && !allowed.test(name)) injected.add(name);
            });
        injected.forEach(target::remove);

        selection
            .discriminatorDefaults()
            .forEach((name, value) -> {
                if (!target.has(name) && allowed.test(name)) target.set(name, value.deepCopy());
            });

        // Pin the branch: its const values make it the one the re-validation matches.
        valueConstraints(schemaRoot, branch).forEach((name, values) -> {
            if (values.size() == 1 && !target.has(name)) target.set(name, values.get(0).deepCopy());
        });
    }

    private JsonNode oneOfContainer(JsonNode schemaRoot, SchemaLocation schemaLocation) {
        String fragment = schemaLocation.getFragment().toString();
        if (fragment.endsWith("/oneOf")) {
            fragment = fragment.substring(0, fragment.length() - "/oneOf".length());
        }
        JsonNode container = navigateSchemaPath(schemaRoot, fragment);
        return container != null && container.has("oneOf") && container.get("oneOf").isArray() ? container : null;
    }

    // -------------------------------------------------------------------------
    // Discriminated if/then variants
    // -------------------------------------------------------------------------

    /**
     * One {@code allOf} entry of the form {@code { "if": { "properties": { "type": { "const": "A" } } }, "then": {...} }}.
     * {@code properties} are the property names the {@code then} branch requires or declares: they belong to the variant.
     */
    private record ConditionalVariant(String discriminator, List<JsonNode> values, Set<String> conditionRequired, Set<String> properties) {
        boolean isActive(ObjectNode object) {
            for (String required : conditionRequired) {
                if (!object.has(required)) return false;
            }
            JsonNode value = object.get(discriminator);
            return value == null || values.contains(value);
        }
    }

    /**
     * Variants expressed with {@code allOf}/{@code if}/{@code then}, grouped by discriminator. Only groups of at least
     * two variants switching on the same property are kept: a single {@code if}/{@code then} (e.g. "host is required when
     * enabled") is a toggle, not a discriminated union, and its properties must survive when the toggle is off.
     */
    private Map<String, List<ConditionalVariant>> conditionalVariants(JsonNode schemaRoot, JsonNode objectSchema) {
        JsonNode allOf = objectSchema.get("allOf");
        if (allOf == null || !allOf.isArray()) return Map.of();

        Map<String, List<ConditionalVariant>> groups = new LinkedHashMap<>();
        for (JsonNode entry : allOf) {
            ConditionalVariant variant = conditionalVariant(schemaRoot, resolveRef(schemaRoot, entry));
            if (variant != null) {
                groups.computeIfAbsent(variant.discriminator(), name -> new ArrayList<>()).add(variant);
            }
        }
        groups.values().removeIf(variants -> variants.size() < 2);
        return groups;
    }

    private ConditionalVariant conditionalVariant(JsonNode schemaRoot, JsonNode entry) {
        if (entry == null || !entry.isObject() || entry.has("else")) return null;
        JsonNode condition = resolveRef(schemaRoot, entry.get("if"));
        JsonNode consequence = resolveRef(schemaRoot, entry.get("then"));
        if (condition == null || consequence == null || !condition.isObject() || !consequence.isObject()) return null;

        // Only the discriminator shape is understood: one property tested with const/enum, optionally required.
        Set<String> conditionKeywords = new HashSet<>();
        condition.fieldNames().forEachRemaining(conditionKeywords::add);
        conditionKeywords.removeAll(Set.of("properties", "required"));
        JsonNode conditionProperties = condition.get("properties");
        Map<String, List<JsonNode>> constraints = valueConstraints(schemaRoot, condition);
        if (!conditionKeywords.isEmpty() || conditionProperties == null || conditionProperties.size() != 1 || constraints.size() != 1) {
            return null;
        }
        Map.Entry<String, List<JsonNode>> discriminator = constraints.entrySet().iterator().next();

        Set<String> properties = stringSet(consequence.get("required"));
        JsonNode consequenceProperties = consequence.get("properties");
        if (consequenceProperties != null && consequenceProperties.isObject()) {
            consequenceProperties.fieldNames().forEachRemaining(properties::add);
        }
        return new ConditionalVariant(discriminator.getKey(), discriminator.getValue(), stringSet(condition.get("required")), properties);
    }

    /**
     * Properties of {@code object} that only belong to inactive if/then variants. Only computed when the object schema
     * forbids additional properties: with {@code additionalProperties: false} the author lists every allowed field, so a
     * field owned by inactive variants only is a leftover of a previous variant (e.g. "clientSecret" kept after
     * switching to CLIENT_CERTIFICATE). Properties required by the object schema itself are never returned.
     */
    private Set<String> inactiveVariantProperties(ObjectNode object, JsonNode schemaRoot, JsonNode objectSchema) {
        JsonNode additionalProperties = objectSchema.get("additionalProperties");
        if (additionalProperties == null || !additionalProperties.isBoolean() || additionalProperties.asBoolean()) return Set.of();

        Map<String, List<ConditionalVariant>> groups = conditionalVariants(schemaRoot, objectSchema);
        if (groups.isEmpty()) return Set.of();

        Set<String> kept = stringSet(objectSchema.get("required"));
        Set<String> inactive = new HashSet<>();
        for (Map.Entry<String, List<ConditionalVariant>> group : groups.entrySet()) {
            kept.add(group.getKey());
            List<ConditionalVariant> variants = group.getValue();
            // An unknown discriminator value activates nothing: leave the data alone, validation reports it.
            if (variants.stream().noneMatch(variant -> variant.isActive(object))) continue;
            for (ConditionalVariant variant : variants) {
                (variant.isActive(object) ? kept : inactive).addAll(variant.properties());
            }
        }
        inactive.removeAll(kept);
        inactive.removeIf(name -> !object.has(name));
        return inactive;
    }

    private boolean pruneInactiveVariantProperties(JsonNode jsonNode, JsonNode schemaRoot, JsonNode schemaNode) {
        if (!(jsonNode instanceof ObjectNode objectNode)) return false;

        JsonNode resolved = resolveRef(schemaRoot, schemaNode);
        if (resolved == null) return false;

        Set<String> inactive = inactiveVariantProperties(objectNode, schemaRoot, resolved);
        inactive.forEach(objectNode::remove);
        boolean pruned = !inactive.isEmpty();

        JsonNode properties = resolved.get("properties");
        if (properties == null) return pruned;

        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            JsonNode child = objectNode.get(entry.getKey());
            JsonNode propSchema = resolveRef(schemaRoot, entry.getValue());
            if (child == null || propSchema == null) continue;

            if (child.isObject()) {
                pruned |= pruneInactiveVariantProperties(child, schemaRoot, propSchema);
            } else if (child.isArray() && propSchema.has("items")) {
                for (JsonNode element : child) {
                    pruned |= pruneInactiveVariantProperties(element, schemaRoot, propSchema.get("items"));
                }
            }
        }
        return pruned;
    }

    /**
     * A default for a discriminator must not switch the object to a variant rejecting fields already present (e.g.
     * defaulting "type" to CLIENT_SECRET while a "certificate" is set): those fields would be silently dropped. The
     * discriminator is then left missing so validation asks for it explicitly.
     */
    private boolean defaultFitsPresentFields(
        ObjectNode object,
        JsonNode schemaRoot,
        JsonNode objectSchema,
        String property,
        JsonNode defaultValue
    ) {
        JsonNode oneOf = objectSchema.get("oneOf");
        boolean hasOneOf = oneOf != null && oneOf.isArray();
        if (!hasOneOf && !objectSchema.has("allOf")) return true;

        ObjectNode withDefault = object.deepCopy();
        withDefault.set(property, defaultValue.deepCopy());

        if (hasOneOf) {
            for (JsonNode branch : oneOf) {
                JsonNode resolved = resolveRef(schemaRoot, branch);
                if (resolved == null || !resolved.has("properties")) continue;
                Map<String, List<JsonNode>> constraints = valueConstraints(schemaRoot, resolved);
                List<JsonNode> values = constraints.get(property);
                boolean designated = values != null && values.size() == 1 && discriminatorMatches(constraints, withDefault) > 0;
                if (designated && !allowedProperties(resolved).allMatch(object)) return false;
            }
        }
        return inactiveVariantProperties(withDefault, schemaRoot, objectSchema).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Schema and instance navigation
    // -------------------------------------------------------------------------

    /** Property names a schema accepts: everything, unless it sets {@code additionalProperties: false}. */
    private record AllowedProperties(Set<String> declared, List<Pattern> patterns, boolean restricted) implements Predicate<String> {
        @Override
        public boolean test(String name) {
            return !restricted || declared.contains(name) || patterns.stream().anyMatch(pattern -> pattern.matcher(name).find());
        }

        boolean allMatch(ObjectNode object) {
            Iterator<String> names = object.fieldNames();
            while (names.hasNext()) {
                if (!test(names.next())) return false;
            }
            return true;
        }
    }

    private AllowedProperties allowedProperties(JsonNode schema) {
        JsonNode additionalProperties = schema.get("additionalProperties");
        if (additionalProperties == null || !additionalProperties.isBoolean() || additionalProperties.asBoolean()) {
            return new AllowedProperties(Set.of(), List.of(), false);
        }

        Set<String> declared = new HashSet<>();
        JsonNode properties = schema.get("properties");
        if (properties != null) {
            properties.fieldNames().forEachRemaining(declared::add);
        }

        List<Pattern> patterns = new ArrayList<>();
        JsonNode patternProperties = schema.get("patternProperties");
        if (patternProperties != null && patternProperties.isObject()) {
            patternProperties
                .fieldNames()
                .forEachRemaining(patternStr -> {
                    try {
                        patterns.add(Pattern.compile(patternStr));
                    } catch (PatternSyntaxException e) {
                        // ignore invalid patterns in schema
                    }
                });
        }
        return new AllowedProperties(declared, patterns, true);
    }

    private static Set<String> stringSet(JsonNode array) {
        Set<String> result = new HashSet<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> result.add(value.asText()));
        }
        return result;
    }

    private JsonNode resolveRef(JsonNode schemaRoot, JsonNode node) {
        return resolveRef(schemaRoot, node, new HashSet<>());
    }

    // visited guards against $ref cycles (e.g. a -> b -> a) which would otherwise StackOverflow.
    private JsonNode resolveRef(JsonNode schemaRoot, JsonNode node, Set<String> visited) {
        if (node == null) return null;
        if (!node.has("$ref")) return node;

        String ref = node.get("$ref").asText();
        if (!visited.add(ref)) return null;

        if ("#".equals(ref)) {
            return resolveRef(schemaRoot, schemaRoot, visited);
        }

        if (ref.startsWith("#/")) {
            String[] parts = ref.substring(2).split("/");
            JsonNode current = schemaRoot;
            for (String part : parts) {
                String unescaped = part.replace("~1", "/").replace("~0", "~");
                current = current.get(unescaped);
                if (current == null) return null;
            }
            return resolveRef(schemaRoot, current, visited);
        }
        return node;
    }

    private JsonNode navigateSchemaPath(JsonNode schemaRoot, String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) return schemaRoot;

        String normalized = path.startsWith("/") ? path.substring(1) : path;
        String[] parts = normalized.split("/");
        JsonNode current = schemaRoot;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (current == null) return null;
            // Handle array indices
            if (current.isArray()) {
                try {
                    int index = Integer.parseInt(part);
                    current = current.get(index);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                String unescaped = part.replace("~1", "/").replace("~0", "~");
                current = current.get(unescaped);
            }
        }
        return current;
    }

    /**
     * The object instance at {@code instancePath}, or {@code null} when the path does not lead to an object (e.g. it was
     * removed by a previous correction). Splits on '.' — property names containing '.' are not supported by the LEGACY
     * path format.
     */
    private ObjectNode findObject(JsonNode root, String instancePath) {
        String normalized = instancePath == null ? "" : instancePath;
        if (normalized.startsWith("$")) normalized = normalized.substring(1);

        JsonNode current = root;
        for (String part : normalized.replace("[", ".[").split("\\.")) {
            if (part.isEmpty()) continue;
            if (current == null) return null;
            if (part.startsWith("[") && part.endsWith("]")) {
                if (!current.isArray()) return null;
                try {
                    current = current.get(Integer.parseInt(part.substring(1, part.length() - 1)));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                current = current.isObject() ? current.get(part) : null;
            }
        }
        return current instanceof ObjectNode target ? target : null;
    }

    /**
     * The schema holding a "required" keyword. The schema location of a "required" error points to the keyword itself
     * (e.g. /required or /allOf/0/then/required).
     */
    private JsonNode requiredOwnerSchema(JsonNode schemaRoot, SchemaLocation schemaLocation) {
        String fragment = schemaLocation.getFragment().toString();
        if (fragment.endsWith("/required")) {
            fragment = fragment.substring(0, fragment.length() - "/required".length());
        }
        return navigateSchemaPath(schemaRoot, fragment);
    }

    // schemaRoot is the document root used to resolve "#/..." $refs; schemaNode is the (sub)schema being walked.
    private void injectOptionalDefaults(JsonNode jsonNode, JsonNode schemaRoot, JsonNode schemaNode) {
        if (!(jsonNode instanceof ObjectNode objectNode)) return;

        JsonNode resolved = resolveRef(schemaRoot, schemaNode);
        if (resolved == null) return;

        JsonNode properties = resolved.get("properties");
        if (properties == null) return;

        // When the schema declares "required", restrict auto-injection to those properties. Non-required
        // fields are treated as opt-in: the caller's omission is intentional, so we don't populate them
        // even when they carry a default. Without a "required" array we fall back to injecting every
        // default — legacy behavior relied on by schemas that lean on defaults instead of requirements.
        Set<String> requiredFields = null;
        JsonNode requiredArray = resolved.get("required");
        if (requiredArray != null && requiredArray.isArray()) {
            requiredFields = stringSet(requiredArray);
        }

        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            String propName = entry.getKey();
            JsonNode propSchema = resolveRef(schemaRoot, entry.getValue());
            if (propSchema == null) continue;

            if (!objectNode.has(propName)) {
                if (
                    propSchema.has("default") &&
                    (requiredFields == null || requiredFields.contains(propName)) &&
                    defaultFitsPresentFields(objectNode, schemaRoot, resolved, propName, propSchema.get("default"))
                ) {
                    objectNode.set(propName, propSchema.get("default").deepCopy());
                }
            } else {
                JsonNode child = objectNode.get(propName);
                if (child.isObject() && propSchema.has("properties")) {
                    injectOptionalDefaults(child, schemaRoot, propSchema);
                } else if (child.isArray() && propSchema.has("items")) {
                    JsonNode itemsSchema = resolveRef(schemaRoot, propSchema.get("items"));
                    if (itemsSchema != null) {
                        for (JsonNode element : child) {
                            if (element.isObject()) {
                                injectOptionalDefaults(element, schemaRoot, itemsSchema);
                            }
                        }
                    }
                }
            }
        }
    }

    private record JavaRegexFormat(String name) implements Format {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean matches(ExecutionContext executionContext, String value) {
            try {
                Pattern.compile(value);
                return true;
            } catch (PatternSyntaxException e) {
                return false;
            }
        }
    }
}
