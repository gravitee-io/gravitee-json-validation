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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonSchemaValidatorImplTest {

    public static final String SIMPLE_SCHEMA = "src/test/resources/schema_simple.json";
    public static final String SCHEMA_WITH_DEFAULT_VALUE = "src/test/resources/schema_default_value.json";
    public static final String SCHEMA_WITH_NESTED_DEFAULT_VALUE = "src/test/resources/schema_nested_default_value.json";
    public static final String SCHEMA_WITH_NOT_ALLOWED_ADDITIONAL_PROPERTIES = "src/test/resources/schema_additional_properties.json";
    public static final String SCHEMA_WITH_NOT_ALLOWED_ADDITIONAL_PROPERTIES_AND_KEEP_PATTERN_PROPERTIES =
        "src/test/resources/schema_additional_properties_pattern_properties.json";
    public static final String SCHEMA_WITH_CUSTOM_REGEX = "src/test/resources/schema_custom_regex.json";

    JsonSchemaValidator validator = new JsonSchemaValidatorImpl();

    @Test
    void should_return_json_content_when_valid() throws IOException {
        String schema = Files.readString(Path.of(SIMPLE_SCHEMA));
        String json = "{\n" + "  \"name\": \"John\",\n" + "  \"age\": 30\n" + "}";
        String result = validator.validate(schema, json);
        assertThat(result).isEqualTo("{\"name\":\"John\",\"age\":30}");
    }

    @Test
    void should_throw_exception_when_invalid() throws IOException {
        String schema = Files.readString(Path.of(SCHEMA_WITH_DEFAULT_VALUE));

        assertThatThrownBy(() -> validator.validate(schema, "{\n" + " \"age\": 30,\n" + "  \"citizenship\": \"FR\"\n" + "}"))
            .isInstanceOf(InvalidJsonException.class)
            .hasMessage("#: required key [name] not found");

        assertThatThrownBy(() -> validator.validate(schema, "{\n" + "  \"name\": \"John\",\n" + " \"age\": true\n" + "}"))
            .isInstanceOf(InvalidJsonException.class)
            .hasMessage("#/age: expected type: Integer, found: Boolean");
    }

    @Test
    void should_return_updated_json_required_default_value_missing() throws IOException {
        String schema = Files.readString(Path.of(SCHEMA_WITH_DEFAULT_VALUE));

        String json = "{\n" + "  \"name\": \"John\",\n" + "  \"age\": 30\n" + "}";
        String result = validator.validate(schema, json);
        assertThat(result).isEqualTo("{\"citizenship\":\"USA\",\"name\":\"John\",\"age\":30}");

        schema = Files.readString(Path.of(SCHEMA_WITH_NESTED_DEFAULT_VALUE));
        result = validator.validate(schema, "{\"additional-property\": true}");
        assertThat(result)
            .isEqualTo("{\"additional-property\":true,\"address\":{\"city\":\"Lille\"},\"citizenship\":\"France\",\"name\":\"John Doe\"}");

        result = validator.validate(schema, "{\"additional-property\": true, \"address\": {}}");
        assertThat(result)
            .isEqualTo("{\"additional-property\":true,\"address\":{\"city\":\"Paris\"},\"citizenship\":\"France\",\"name\":\"John Doe\"}");
    }

    @Test
    void should_return_updated_json_with_additional_properties_removed() throws IOException {
        String schema = Files.readString(Path.of(SCHEMA_WITH_NOT_ALLOWED_ADDITIONAL_PROPERTIES));

        String json = "{\n" + "  \"name\": \"John\",\n" + "  \"age\": 30,\n" + "  \"unknown\": 30\n" + "}";
        String result = validator.validate(schema, json);
        assertThat(result).isEqualTo("{\"name\":\"John\",\"age\":30}");
    }

    @Test
    void should_return_updated_json_with_additional_properties_removed_keeping_pattern_properties() throws IOException {
        String schema = Files.readString(Path.of(SCHEMA_WITH_NOT_ALLOWED_ADDITIONAL_PROPERTIES_AND_KEEP_PATTERN_PROPERTIES));

        String json = "{\n" + "  \"name\": \"John\",\n" + "  \"to-keep\": \"value\",\n" + "  \"age\": 30,\n" + "  \"unknown\": 30\n" + "}";
        String result = validator.validate(schema, json);
        assertThat(result).isEqualTo("{\"name\":\"John\",\"to-keep\":\"value\",\"age\":30}");
    }

    @ParameterizedTest
    @ValueSource(strings = { "regex", "java-regex" })
    public void should_return_valid_json_with_schema_using_custom_java_regex(String regexFormat) throws Exception {
        String schema = Files.readString(Path.of(SCHEMA_WITH_CUSTOM_REGEX)).replace("regex", regexFormat);
        String json = "{ \"name\": \"^.*[A-Za-z]\\\\d*$\", \"valid\": true }";
        String result = validator.validate(schema, json);
        assertThat(result).isEqualTo("{\"valid\":true,\"name\":\"^.*[A-Za-z]\\\\d*$\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = { "regex", "java-regex" })
    public void should_reject_invalid_json_due_to_invalid_regex(String regexFormat) throws Exception {
        String schema = Files.readString(Path.of(SCHEMA_WITH_CUSTOM_REGEX)).replace("regex", regexFormat);

        assertThatThrownBy(() -> validator.validate(schema, "{ \"name\": \"( INVALID regex\", \"valid\": true }"))
            .isInstanceOf(InvalidJsonException.class)
            .hasMessage("#/name: [( INVALID regex] is not a valid regular expression");
    }
}
