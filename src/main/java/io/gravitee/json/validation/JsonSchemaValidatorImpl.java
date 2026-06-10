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
     *   - pathType(PathType.LEGACY) — emits $.foo.bar style paths (matches old everit output, keeps navigateToTarget happy).
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

                List<com.networknt.schema.Error> errors = schemaValidator.validate(jsonNode);
                if (!errors.isEmpty()) {
                    jsonNode = processErrors(jsonNode, schemaNode, errors);
                }

                injectOptionalDefaults(jsonNode, schemaNode, schemaNode);

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
     * Applies recovery mutations (default injection, additionalProperties pruning, oneOf cleanup) atomically:
     * all work happens on a deep copy, so a partway {@link InvalidJsonException} leaves the original node
     * untouched. Returns the mutated copy on success; the caller swaps to it for the final serialization.
     */
    private JsonNode processErrors(JsonNode original, JsonNode schemaNode, List<com.networknt.schema.Error> errors) {
        JsonNode jsonNode = original.deepCopy();
        List<String> unrecoverableErrors = new ArrayList<>();
        Set<String> oneOfPaths = new HashSet<>();

        // First pass: handle oneOf errors and collect their paths.
        // The oneOf handler injects parent-level defaults to drive discriminator matching, then prunes the
        // ones that don't belong to the matching subschema (see cleanupSpuriousDefaults).
        for (com.networknt.schema.Error error : errors) {
            if ("oneOf".equals(error.getKeyword())) {
                oneOfPaths.add(error.getInstanceLocation().toString());
                handleOneOfError(jsonNode, schemaNode, error, unrecoverableErrors);
            }
        }

        // Second pass: handle remaining errors, skipping sub-errors that belong to a oneOf path
        for (com.networknt.schema.Error error : errors) {
            String keyword = error.getKeyword();
            if ("oneOf".equals(keyword)) continue;

            String instancePath = error.getInstanceLocation().toString();
            String schemaFragment = error.getSchemaLocation().getFragment().toString();

            // Skip errors that are sub-errors of a oneOf validation (they contain /oneOf/ in schema path)
            // and whose instance is within a path already handled by oneOf
            if (schemaFragment.contains("/oneOf/") && isWithinOneOfPath(instancePath, oneOfPaths)) {
                continue;
            }

            switch (keyword) {
                case "required" -> handleRequiredError(jsonNode, schemaNode, error, unrecoverableErrors);
                case "additionalProperties" -> handleAdditionalPropertiesError(jsonNode, error);
                case "allOf" -> {}
                case "const" -> {
                    // const errors within oneOf are handled by the oneOf handler
                    if (!isWithinOneOfPath(instancePath, oneOfPaths)) {
                        unrecoverableErrors.add(error.toString());
                    }
                }
                default -> unrecoverableErrors.add(error.toString());
            }
        }

        if (!unrecoverableErrors.isEmpty()) {
            throw new InvalidJsonException(String.join("\n", unrecoverableErrors));
        }

        return jsonNode;
    }

    private boolean isWithinOneOfPath(String instancePath, Set<String> oneOfPaths) {
        for (String oneOfPath : oneOfPaths) {
            if (instancePath.equals(oneOfPath)) {
                return true;
            }
            // Segment-aware containment: a true descendant has the oneOf path followed by a path
            // separator ('.' for object keys, '[' for array elements in LEGACY format). A raw
            // startsWith would falsely match a sibling like "$.httpProxy" against oneOf path "$.http".
            if (instancePath.startsWith(oneOfPath) && instancePath.length() > oneOfPath.length()) {
                char next = instancePath.charAt(oneOfPath.length());
                if (next == '.' || next == '[') {
                    return true;
                }
            }
        }
        return false;
    }

    private void handleRequiredError(
        JsonNode jsonNode,
        JsonNode schemaNode,
        com.networknt.schema.Error error,
        List<String> unrecoverableErrors
    ) {
        Object[] arguments = error.getArguments();
        if (arguments == null || arguments.length == 0) {
            unrecoverableErrors.add(error.toString());
            return;
        }
        String missingField = arguments[0].toString();
        String instancePath = error.getInstanceLocation().toString();

        JsonNode propertySchema = resolvePropertySchema(schemaNode, error.getSchemaLocation(), missingField);

        if (propertySchema != null && propertySchema.has("default")) {
            JsonNode defaultValue = propertySchema.get("default");
            ObjectNode target = navigateToTarget(jsonNode, instancePath);
            if (!target.has(missingField)) {
                target.set(missingField, defaultValue.deepCopy());
            }
        } else {
            unrecoverableErrors.add(error.toString());
        }
    }

    private void handleAdditionalPropertiesError(JsonNode jsonNode, com.networknt.schema.Error error) {
        Object[] arguments = error.getArguments();
        if (arguments == null || arguments.length == 0) {
            return;
        }
        String extraField = arguments[0].toString();
        String instancePath = error.getInstanceLocation().toString();
        ObjectNode target = navigateToTarget(jsonNode, instancePath);
        target.remove(extraField);
    }

    private void handleOneOfError(
        JsonNode jsonNode,
        JsonNode schemaNode,
        com.networknt.schema.Error error,
        List<String> unrecoverableErrors
    ) {
        String instancePath = error.getInstanceLocation().toString();
        ObjectNode targetObject = navigateToTarget(jsonNode, instancePath);

        JsonNode oneOfArray = findOneOfArray(schemaNode, error.getSchemaLocation());
        if (oneOfArray == null || !oneOfArray.isArray()) {
            unrecoverableErrors.add(error.toString());
            return;
        }

        // Inject property defaults from the schema containing the oneOf, so discriminator
        // fields with defaults are present for const matching.
        String schemaFragment = error.getSchemaLocation().getFragment().toString();
        String parentPath = schemaFragment.contains("/") ? schemaFragment.substring(0, schemaFragment.lastIndexOf('/')) : schemaFragment;
        if (parentPath.isEmpty()) parentPath = "/";
        JsonNode parentSchemaNode = navigateSchemaPath(schemaNode, parentPath);
        if (parentSchemaNode != null) {
            injectOptionalDefaults(targetObject, schemaNode, parentSchemaNode);
        }

        JsonNode matchingSubschema = findMatchingSubschema(schemaNode, oneOfArray, targetObject);
        if (matchingSubschema == null) {
            unrecoverableErrors.add(error.toString());
            return;
        }

        // Remove properties not allowed by the matching subschema. The parent-default injection above is
        // schema-wide, so it can add fields belonging to other branches; when the matching subschema has
        // additionalProperties: false, drop anything it doesn't define.
        cleanupSpuriousDefaults(matchingSubschema, targetObject);

        if (validatorPrefilledDefaults(schemaNode, matchingSubschema, targetObject)) {
            return;
        }

        Map<String, JsonNode> defaults = findSubschemaDefaults(schemaNode, matchingSubschema, targetObject);
        if (defaults.isEmpty()) {
            unrecoverableErrors.add(error.toString());
            return;
        }

        for (Map.Entry<String, JsonNode> entry : defaults.entrySet()) {
            targetObject.set(entry.getKey(), entry.getValue().deepCopy());
        }
    }

    private void cleanupSpuriousDefaults(JsonNode matchingSubschema, ObjectNode targetObject) {
        JsonNode additionalProps = matchingSubschema.get("additionalProperties");
        if (additionalProps != null && additionalProps.isBoolean() && !additionalProps.asBoolean()) {
            Set<String> allowedProps = new HashSet<>();
            JsonNode properties = matchingSubschema.get("properties");
            if (properties != null) {
                properties.fieldNames().forEachRemaining(allowedProps::add);
            }

            List<Pattern> allowedPatterns = new ArrayList<>();
            JsonNode patternProperties = matchingSubschema.get("patternProperties");
            if (patternProperties != null && patternProperties.isObject()) {
                patternProperties
                    .fieldNames()
                    .forEachRemaining(patternStr -> {
                        try {
                            allowedPatterns.add(Pattern.compile(patternStr));
                        } catch (PatternSyntaxException e) {
                            // ignore invalid patterns in schema
                        }
                    });
            }

            List<String> toRemove = new ArrayList<>();
            targetObject
                .fieldNames()
                .forEachRemaining(field -> {
                    if (allowedProps.contains(field)) {
                        return;
                    }
                    for (Pattern pattern : allowedPatterns) {
                        if (pattern.matcher(field).find()) {
                            return;
                        }
                    }
                    toRemove.add(field);
                });
            toRemove.forEach(targetObject::remove);
        }
    }

    private JsonNode findOneOfArray(JsonNode schemaRoot, SchemaLocation schemaLocation) {
        String fragment = schemaLocation.getFragment().toString();
        JsonNode node = navigateSchemaPath(schemaRoot, fragment);
        if (node != null && node.has("oneOf")) {
            return node.get("oneOf");
        }
        // The schemaLocation might point directly at the oneOf keyword
        if (node != null && node.isArray()) {
            return node;
        }
        return null;
    }

    private JsonNode findMatchingSubschema(JsonNode schemaRoot, JsonNode oneOfArray, ObjectNode config) {
        JsonNode firstCompatible = null;

        for (JsonNode subschema : oneOfArray) {
            JsonNode resolved = resolveRef(schemaRoot, subschema);
            if (resolved == null || !resolved.has("properties")) continue;

            boolean compatible = true; // input violates none of this branch's const constraints
            boolean exactMatch = false; // input supplied a value equal to one of this branch's const

            JsonNode properties = resolved.get("properties");
            for (Map.Entry<String, JsonNode> entry : properties.properties()) {
                String propName = entry.getKey();
                JsonNode propSchema = resolveRef(schemaRoot, entry.getValue());

                if (propSchema != null && propSchema.has("const") && config.has(propName)) {
                    if (config.get(propName).equals(propSchema.get("const"))) {
                        exactMatch = true;
                    } else {
                        compatible = false;
                    }
                }
            }

            // A branch whose discriminator the input explicitly matches wins outright.
            if (exactMatch && compatible) {
                return resolved;
            }
            // Otherwise keep the first branch the input does not contradict: covers unions without
            // const discriminators, inputs that omit the discriminator, and free-form branches that
            // accept the supplied value.
            if (compatible && firstCompatible == null) {
                firstCompatible = resolved;
            }
        }

        // null when the input supplied a value conflicting with every branch's const: a genuine
        // validation error. The caller surfaces the original oneOf error instead of silently
        // injecting defaults into a mismatched branch.
        return firstCompatible;
    }

    private boolean validatorPrefilledDefaults(JsonNode schemaRoot, JsonNode subschema, ObjectNode targetObject) {
        JsonNode requiredArray = subschema.get("required");
        if (requiredArray != null) {
            for (JsonNode req : requiredArray) {
                if (!targetObject.has(req.asText())) return false;
            }
        }

        JsonNode properties = subschema.get("properties");
        if (properties != null) {
            for (Map.Entry<String, JsonNode> entry : properties.properties()) {
                JsonNode propSchema = resolveRef(schemaRoot, entry.getValue());
                if (propSchema != null && propSchema.has("const")) {
                    JsonNode constVal = propSchema.get("const");
                    if (!targetObject.has(entry.getKey()) || !constVal.equals(targetObject.get(entry.getKey()))) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private Map<String, JsonNode> findSubschemaDefaults(JsonNode schemaRoot, JsonNode subschema, ObjectNode config) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        Set<String> requiredProps = new HashSet<>();

        JsonNode requiredArray = subschema.get("required");
        if (requiredArray != null) {
            for (JsonNode r : requiredArray) requiredProps.add(r.asText());
        }

        JsonNode properties = subschema.get("properties");
        if (properties == null) return result;

        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            String propName = entry.getKey();
            if (config.has(propName)) continue;

            JsonNode propSchema = resolveRef(schemaRoot, entry.getValue());
            if (propSchema == null) continue;

            if (propSchema.has("const")) {
                result.put(propName, propSchema.get("const"));
            } else if (requiredProps.contains(propName) && propSchema.has("default")) {
                result.put(propName, propSchema.get("default"));
            }
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

    // Splits on '.' — property names containing '.' are not supported by the LEGACY path format.
    private ObjectNode navigateToTarget(JsonNode root, String instancePath) {
        if (!(root instanceof ObjectNode objectNode)) {
            throw new InvalidJsonException("Expected JSON object at path: " + instancePath);
        }
        if (instancePath == null || instancePath.isEmpty() || "$".equals(instancePath)) {
            return objectNode;
        }

        // Handle legacy path format like "$.http.endpoint" or just path without $
        String normalized = instancePath.startsWith("$.") ? instancePath.substring(2) : instancePath;
        if (normalized.startsWith("$")) normalized = normalized.substring(1);
        if (normalized.isEmpty()) return objectNode;

        String prepared = normalized.replace("[", ".[");
        String[] parts = prepared.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (part.startsWith("[") && part.endsWith("]")) {
                if (!current.isArray()) {
                    throw new InvalidJsonException("Cannot navigate to path '" + instancePath + "': expected array at '" + part + "'");
                }
                try {
                    int index = Integer.parseInt(part.substring(1, part.length() - 1));
                    if (index < 0 || index >= current.size()) {
                        throw new InvalidJsonException(
                            "Cannot navigate to path '" + instancePath + "': index out of bounds '" + part + "'"
                        );
                    }
                    current = current.get(index);
                } catch (NumberFormatException e) {
                    throw new InvalidJsonException("Cannot navigate to path '" + instancePath + "': invalid array index '" + part + "'");
                }
            } else {
                if (!current.isObject() || !current.has(part)) {
                    throw new InvalidJsonException("Cannot navigate to path '" + instancePath + "': missing segment '" + part + "'");
                }
                current = current.get(part);
            }
        }
        if (current instanceof ObjectNode target) {
            return target;
        }
        throw new InvalidJsonException("Path '" + instancePath + "' does not resolve to a JSON object");
    }

    private JsonNode resolvePropertySchema(JsonNode schemaRoot, SchemaLocation schemaLocation, String propertyName) {
        String fragment = schemaLocation.getFragment().toString();
        // The schema location for "required" errors points to the "required" keyword itself (e.g. /required or /allOf/0/then/required).
        // Navigate to the parent object that contains both "required" and "properties".
        if (fragment.endsWith("/required")) {
            fragment = fragment.substring(0, fragment.length() - "/required".length());
            if (fragment.isEmpty()) fragment = "/";
        }
        JsonNode schemaNode = navigateSchemaPath(schemaRoot, fragment);
        if (schemaNode == null) return null;

        JsonNode props = schemaNode.get("properties");
        if (props != null && props.has(propertyName)) {
            return resolveRef(schemaRoot, props.get(propertyName));
        }
        return null;
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
            requiredFields = new HashSet<>();
            for (JsonNode r : requiredArray) requiredFields.add(r.asText());
        }

        for (Map.Entry<String, JsonNode> entry : properties.properties()) {
            String propName = entry.getKey();
            JsonNode propSchema = resolveRef(schemaRoot, entry.getValue());
            if (propSchema == null) continue;

            if (!objectNode.has(propName)) {
                if (propSchema.has("default") && (requiredFields == null || requiredFields.contains(propName))) {
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
