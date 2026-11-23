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

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.ExecutionContext;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaException;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.keyword.AbstractKeyword;
import com.networknt.schema.keyword.AbstractKeywordValidator;
import com.networknt.schema.keyword.KeywordValidator;
import com.networknt.schema.path.NodePath;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@code uniqueItemProperties} keyword, inspired by ajv-keywords: each property it lists must have a different value
 * in every object item of the array, {@code "uniqueItemProperties": ["name"]}. Each property is checked on its own, and
 * numbers are compared by value, {@code 1} and {@code 1.0} being the same.
 * <p>
 * Unlike ajv-keywords, items that are not objects, or that do not have the property, are ignored: make the property
 * {@code required} to forbid them. A listed property cannot have a {@code default}, which would be copied into every item
 * missing it (see {@link JsonSchemaValidatorImpl}).
 * <p>
 * Standard JSON Schema has no way to say this: {@code uniqueItems} compares whole items.
 */
class UniqueItemPropertiesKeyword extends AbstractKeyword {

    static final String KEYWORD = "uniqueItemProperties";

    UniqueItemPropertiesKeyword() {
        super(KEYWORD);
    }

    @Override
    public KeywordValidator newValidator(
        SchemaLocation schemaLocation,
        JsonNode schemaNode,
        Schema parentSchema,
        SchemaContext schemaContext
    ) {
        return new Validator(this, schemaNode, schemaLocation);
    }

    private static final class Validator extends AbstractKeywordValidator {

        private final List<String> properties;

        private Validator(UniqueItemPropertiesKeyword keyword, JsonNode schemaNode, SchemaLocation schemaLocation) {
            super(keyword, schemaNode, schemaLocation);
            if (!schemaNode.isArray()) {
                throw new SchemaException(schemaLocation + ": " + KEYWORD + " must be an array of property names");
            }
            List<String> names = new ArrayList<>();
            for (JsonNode name : schemaNode) {
                if (!name.isTextual()) {
                    throw new SchemaException(schemaLocation + ": " + KEYWORD + " must be an array of property names");
                }
                names.add(name.textValue());
            }
            this.properties = List.copyOf(names);
        }

        @Override
        public void validate(ExecutionContext executionContext, JsonNode node, JsonNode rootNode, NodePath instanceLocation) {
            if (!node.isArray()) {
                return;
            }
            for (String property : properties) {
                Set<Object> seen = new HashSet<>();
                Set<Object> repeated = new HashSet<>();
                for (JsonNode item : node) {
                    JsonNode value = item.isObject() ? item.get(property) : null;
                    if (value == null) continue;
                    // JsonNode equality tells IntNode(1) from DoubleNode(1.0): numbers are compared by value instead.
                    Object key = value.isNumber() ? value.decimalValue().stripTrailingZeros() : value;
                    if (!seen.add(key) && repeated.add(key)) {
                        executionContext.addError(
                            Error.builder()
                                .keyword(KEYWORD)
                                .property(property)
                                .instanceNode(node)
                                .instanceLocation(instanceLocation)
                                .evaluationPath(executionContext.getEvaluationPath())
                                .schemaLocation(schemaLocation)
                                .schemaNode(schemaNode)
                                .arguments(property, value.toString())
                                .message("must have a different {0} on each item, but {1} is repeated")
                                .build()
                        );
                    }
                }
            }
        }
    }
}
