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

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.everit.json.schema.ObjectSchema;
import org.everit.json.schema.Schema;
import org.everit.json.schema.SchemaLocation;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.internal.RegexFormatValidator;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;

public class JsonSchemaValidatorImpl implements JsonSchemaValidator {

    private final Pattern errorFieldNamePattern = Pattern.compile("\\[(.*?)\\]");

    @Override
    public String validate(String schema, String json) {
        // At least, validate json.
        String safeConfiguration = clearNullValues(json);

        if (schema != null && !schema.equals("")) {
            JSONObject schemaJson = new JSONObject(schema);
            JSONObject safeConfigurationJson = new JSONObject(safeConfiguration);
            Schema schemaValidator = SchemaLoader
                .builder()
                .useDefaults(true)
                .addFormatValidator(new JavaRegexValidator())
                .schemaJson(schemaJson)
                .draftV7Support()
                .build()
                .load()
                .build();
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

    private static class JavaRegexValidator extends RegexFormatValidator {

        // Just for retro-compatibility with old validator
        @Override
        public String formatName() {
            return "java-regex";
        }
    }
}
