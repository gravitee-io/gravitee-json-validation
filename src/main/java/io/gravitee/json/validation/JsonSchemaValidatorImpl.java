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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.everit.json.schema.CombinedSchema;
import org.everit.json.schema.ConstSchema;
import org.everit.json.schema.ObjectSchema;
import org.everit.json.schema.ReferenceSchema;
import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.internal.RegexFormatValidator;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;

public class JsonSchemaValidatorImpl implements JsonSchemaValidator {

    private final Pattern errorFieldNamePattern = Pattern.compile("\\[(.*?)\\]");

    private static final long MAX_CACHE_SIZE = 1000L;
    private static final Cache<String, Schema> SCHEMA_CACHE = Caffeine.newBuilder().maximumSize(MAX_CACHE_SIZE).build();

    @Override
    public String validate(String schema, String json) {
        // At least, validate json.
        String safeConfiguration = clearNullValues(json);

        if (schema != null && !schema.isEmpty()) {
            JSONObject safeConfigurationJson = new JSONObject(safeConfiguration);
            Schema schemaValidator = getSchemaValidator(schema);
            // Validate json against schema when defined.
            try {
                schemaValidator.validate(safeConfigurationJson);
            } catch (ValidationException e) {
                if (e.getCausingExceptions().isEmpty()) {
                    checkAndUpdate(safeConfigurationJson, e);
                } else {
                    e.getCausingExceptions().forEach(cause -> checkAndUpdate(safeConfigurationJson, cause));
                }
            }
            return safeConfigurationJson.toString();
        }
        return safeConfiguration;
    }

    private Schema getSchemaValidator(String schemaDefinition) {
        return SCHEMA_CACHE.get(schemaDefinition, key -> buildSchema(new JSONObject(key)));
    }

    private JSONObject checkAndUpdate(JSONObject safeConfigurationJson, ValidationException validationException) {
        if ("required".equalsIgnoreCase(validationException.getKeyword())) {
            Optional<String> errorField = getField(validationException);
            if (errorField.isPresent()) {
                ObjectSchema violatedSchema = (ObjectSchema) validationException.getViolatedSchema();
                Schema schemaField = violatedSchema.getPropertySchemas().get(errorField.get());
                if (schemaField.hasDefaultValue()) {
                    String pointerToViolation = validationException.getPointerToViolation();
                    updateProperty(pointerToViolation, errorField.get(), schemaField.getDefaultValue(), safeConfigurationJson);
                } else {
                    throw new InvalidJsonException(validationException);
                }
            }
        } else if ("additionalProperties".equalsIgnoreCase(validationException.getKeyword())) {
            Optional<String> errorField = getField(validationException);
            if (errorField.isPresent()) {
                safeConfigurationJson.remove(errorField.get());
            } else {
                throw new InvalidJsonException(validationException);
            }
        } else if ("oneOf".equalsIgnoreCase(validationException.getKeyword())) {
            handleOneOfError(safeConfigurationJson, validationException);
        } else if ("allOf".equalsIgnoreCase(validationException.getKeyword())) {
            // In some complex cases, errors are raised under the allOf keyword, but we need to work with the causing exception
            // It is acceptable right now to not use a true recursion, and only control a one level depth
            if (!validationException.getCausingExceptions().isEmpty()) {
                validationException.getCausingExceptions().forEach(cause -> checkAndUpdate(safeConfigurationJson, cause));
            } else {
                throw new InvalidJsonException(validationException);
            }
        } else {
            throw new InvalidJsonException(validationException);
        }
        return safeConfigurationJson;
    }

    private void updateProperty(String pointerToViolation, String key, Object value, JSONObject target) {
        Arrays
            .stream(pointerToViolation.split("/"))
            .filter(token -> !token.equals("properties") && !token.isEmpty())
            .map(property -> {
                if (!property.equals("#")) {
                    if (!target.has(property)) {
                        target.put(property, new JSONObject());
                    }
                    return target.getJSONObject(property);
                }
                return target;
            })
            .reduce((toForgot, toKeep) -> toKeep)
            .ifPresent(propertyToUpdate -> propertyToUpdate.put(key, value));
    }

    private Optional<String> getField(ValidationException validationException) {
        Matcher matcher = errorFieldNamePattern.matcher(validationException.getErrorMessage());
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }
        return Optional.empty();
    }

    private void handleOneOfError(JSONObject config, ValidationException exception) {
        CombinedSchema combinedSchema = (CombinedSchema) exception.getViolatedSchema();
        String pointerToViolation = exception.getPointerToViolation();

        // Navigate to the target object in the config
        JSONObject targetObject = navigateToTarget(config, pointerToViolation);

        // Find the matching subschema based on discriminator, or default to the first one
        ObjectSchema matchingSubschema = findMatchingSubschema(combinedSchema, targetObject);

        if (matchingSubschema == null) {
            throw new InvalidJsonException(exception);
        }

        // Find and inject defaults from the matching subschema
        Map<String, Object> defaults = findSubschemaDefaults(matchingSubschema, targetObject);

        if (defaults.isEmpty()) {
            throw new InvalidJsonException(exception);
        }

        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            targetObject.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Finds the subschema that matches the discriminator value in the config.
     * If no discriminator is found, returns the first subschema as default.
     */
    private ObjectSchema findMatchingSubschema(CombinedSchema schema, JSONObject config) {
        ObjectSchema firstSubschema = null;

        for (Schema subschema : schema.getSubschemas()) {
            ObjectSchema objSchema = unwrapToObjectSchema(subschema);
            if (objSchema == null) {
                continue;
            }

            if (firstSubschema == null) {
                firstSubschema = objSchema;
            }

            // Check if this subschema has a const property that matches a value in config
            for (Map.Entry<String, Schema> entry : objSchema.getPropertySchemas().entrySet()) {
                String propName = entry.getKey();
                Schema propSchema = unwrapSchema(entry.getValue());

                if (propSchema instanceof ConstSchema) {
                    Object constValue = ((ConstSchema) propSchema).getPermittedValue();
                    // If config has this property with the matching const value, this is our subschema
                    if (config.has(propName) && constValue.equals(config.get(propName))) {
                        return objSchema;
                    }
                }
            }
        }

        // No discriminator found in config, return the first subschema as default
        return firstSubschema;
    }

    /**
     * Finds all defaults to inject for a given subschema:
     * - const values for properties not in config (discriminators)
     * - default values for required properties not in config
     */
    private Map<String, Object> findSubschemaDefaults(ObjectSchema subschema, JSONObject config) {
        Map<String, Object> result = new HashMap<>();
        Set<String> requiredProps = new HashSet<>(subschema.getRequiredProperties());

        for (Map.Entry<String, Schema> entry : subschema.getPropertySchemas().entrySet()) {
            String propName = entry.getKey();
            Schema propSchema = unwrapSchema(entry.getValue());

            // Skip if already in config
            if (config.has(propName)) {
                continue;
            }

            // Inject const values (discriminators)
            if (propSchema instanceof ConstSchema) {
                result.put(propName, ((ConstSchema) propSchema).getPermittedValue());
            }
            // Inject default values for required properties
            else if (requiredProps.contains(propName) && propSchema != null && propSchema.hasDefaultValue()) {
                result.put(propName, propSchema.getDefaultValue());
            }
        }

        return result;
    }

    private Schema unwrapSchema(Schema schema) {
        if (schema instanceof ReferenceSchema) {
            return ((ReferenceSchema) schema).getReferredSchema();
        }
        return schema;
    }

    private ObjectSchema unwrapToObjectSchema(Schema schema) {
        Schema unwrapped = unwrapSchema(schema);
        return (unwrapped instanceof ObjectSchema) ? (ObjectSchema) unwrapped : null;
    }

    private JSONObject navigateToTarget(JSONObject root, String pointer) {
        if ("#".equals(pointer) || pointer.isEmpty()) {
            return root;
        }
        String[] parts = pointer.replaceFirst("^#/?", "").split("/");
        JSONObject current = root;
        for (String part : parts) {
            if (!part.isEmpty() && current.has(part)) {
                current = current.getJSONObject(part);
            }
        }
        return current;
    }

    private static Schema buildSchema(JSONObject schemaJson) {
        return SchemaLoader
            .builder()
            .useDefaults(true)
            .addFormatValidator(new JavaRegexValidator())
            .schemaJson(schemaJson)
            .draftV7Support()
            .build()
            .load()
            .build();
    }

    private static class JavaRegexValidator extends RegexFormatValidator {

        // Just for retro-compatibility with old validator
        @Override
        public String formatName() {
            return "java-regex";
        }
    }
}
