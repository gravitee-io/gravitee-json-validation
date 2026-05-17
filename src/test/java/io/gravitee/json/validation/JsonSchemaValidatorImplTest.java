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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Cache;
import com.networknt.schema.Schema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonSchemaValidatorImplTest {

    // --- Cache testing helpers ---
    @SuppressWarnings("unchecked")
    private static Cache<String, Schema> getCache() {
        try {
            java.lang.reflect.Field f = JsonSchemaValidatorImpl.class.getDeclaredField("SCHEMA_CACHE");
            f.setAccessible(true);
            return (Cache<String, Schema>) f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void clearCache() {
        getCache().invalidateAll();
    }

    private static long cacheSize() {
        return getCache().estimatedSize();
    }

    public static final String SIMPLE_SCHEMA = "src/test/resources/schema_simple.json";
    public static final String SCHEMA_WITH_DEFAULT_VALUE = "src/test/resources/schema_default_value.json";
    public static final String SCHEMA_WITH_NESTED_DEFAULT_VALUE = "src/test/resources/schema_nested_default_value.json";
    public static final String SCHEMA_WITH_NOT_ALLOWED_ADDITIONAL_PROPERTIES = "src/test/resources/schema_additional_properties.json";
    public static final String SCHEMA_WITH_NOT_ALLOWED_ADDITIONAL_PROPERTIES_AND_KEEP_PATTERN_PROPERTIES =
        "src/test/resources/schema_additional_properties_pattern_properties.json";
    public static final String SCHEMA_WITH_CUSTOM_REGEX = "src/test/resources/schema_custom_regex.json";
    public static final String SCHEMA_WITH_ALLOF_AND_DEFAULT_VALUE = "src/test/resources/schema_allof_default_value.json";
    public static final String SCHEMA_WITH_ONEOF_AND_DEFAULT_VALUE = "src/test/resources/schema_oneof_default_value.json";
    public static final String SCHEMA_WITH_ONEOF_NO_COMMON_DEFAULT = "src/test/resources/schema_oneof_no_common_default.json";
    public static final String SCHEMA_WITH_ONEOF_PARTIAL_REQUIRED = "src/test/resources/schema_oneof_partial_required.json";
    public static final String SCHEMA_WITH_ALLOF_WRAPPING_ONEOF = "src/test/resources/schema_allof_wrapping_oneof.json";
    public static final String SCHEMA_WITH_REF_TO_ONEOF = "src/test/resources/schema_real_case.json";
    public static final String SCHEMA_WITH_ONEOF_MULTIPLE_MATCH = "src/test/resources/schema_oneof_multiple_match.json";
    public static final String SCHEMA_WITH_ONEOF_REQUIRED_WITH_ROOT_DEFAULTS =
        "src/test/resources/schema_oneof_required_with_root_defaults.json";
    public static final String SCHEMA_WITH_ROOT_REF = "src/test/resources/schema_root_ref.json";
    public static final String SCHEMA_MULTIPLE_REQUIRED_NO_DEFAULTS = "src/test/resources/schema_multiple_required_no_defaults.json";
    public static final String SCHEMA_DEEPLY_NESTED_DEFAULTS = "src/test/resources/schema_deeply_nested_defaults.json";
    public static final String SCHEMA_NESTED_ADDITIONAL_PROPERTIES = "src/test/resources/schema_nested_additional_properties.json";
    public static final String SCHEMA_ONEOF_DISCRIMINATOR_WITH_REQUIRED =
        "src/test/resources/schema_oneof_discriminator_with_required.json";
    public static final String SCHEMA_ONEOF_NO_DISCRIMINATOR = "src/test/resources/schema_oneof_no_discriminator.json";
    public static final String SCHEMA_ONEOF_MIXED_CONST_FREEFORM = "src/test/resources/schema_oneof_mixed_const_freeform.json";
    public static final String SCHEMA_ONEOF_PATTERN_PROPERTIES = "src/test/resources/schema_oneof_pattern_properties.json";

    JsonSchemaValidator validator = new JsonSchemaValidatorImpl();

    @BeforeEach
    void setUp() {
        clearCache();
    }

    // -------------------------------------------------------------------------
    // Basic validation
    // -------------------------------------------------------------------------

    @Nested
    class BasicValidation {

        @Test
        void should_return_json_content_when_valid() throws IOException {
            String schema = Files.readString(Path.of(SIMPLE_SCHEMA));
            String json = """
                { "name": "John",  "age": 30 }""";
            String result = validator.validate(schema, json);
            assertThatJson(result).isEqualTo(json);
        }

        @Test
        void should_throw_when_required_field_missing() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_DEFAULT_VALUE));

            assertThatThrownBy(() ->
                validator.validate(
                    schema,
                    """
                    { "age": 30, "citizenship": "FR" }"""
                )
            )
                .isInstanceOf(InvalidJsonException.class)
                .hasMessage("$: required property 'name' not found");
        }

        @Test
        void should_throw_when_field_has_wrong_type() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_DEFAULT_VALUE));

            assertThatThrownBy(() ->
                validator.validate(
                    schema,
                    """
                    { "name": "John", "age": true }"""
                )
            )
                .isInstanceOf(InvalidJsonException.class)
                .hasMessage("$.age: boolean found, integer expected");
        }

        @Test
        void should_throw_when_multiple_required_fields_missing_without_defaults() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_MULTIPLE_REQUIRED_NO_DEFAULTS));

            assertThatThrownBy(() -> validator.validate(schema, "{}"))
                .isInstanceOf(InvalidJsonException.class)
                .hasMessage(
                    """
                    $: required property 'firstName' not found
                    $: required property 'lastName' not found
                    $: required property 'email' not found"""
                );
        }
    }

    // -------------------------------------------------------------------------
    // Default value injection
    // -------------------------------------------------------------------------

    @Nested
    class DefaultInjection {

        @Test
        void should_inject_simple_default_value() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_DEFAULT_VALUE));

            String json = """
                { "name": "John",  "age": 30 }""";
            String result = validator.validate(schema, json);
            assertThatJson(result).isEqualTo(
                """
                {"citizenship":"USA","name":"John","age":30}"""
            );
        }

        @Test
        void should_inject_nested_defaults_from_empty_object() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_NESTED_DEFAULT_VALUE));

            String result = validator.validate(schema, "{\"additional-property\": true}");
            assertThatJson(result).isEqualTo(
                """
                {"additional-property":true,"address":{"city":"Lille"},"citizenship":"France","name":"John Doe"}"""
            );
        }

        @Test
        void should_inject_nested_defaults_when_parent_exists_but_child_missing() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_NESTED_DEFAULT_VALUE));

            String result = validator.validate(
                schema,
                """
                {"additional-property": true, "address": {}}"""
            );
            assertThatJson(result).isEqualTo(
                """
                {"additional-property":true,"address":{"city":"Paris"},"citizenship":"France","name":"John Doe"}"""
            );
        }

        @Test
        void should_inject_defaults_with_allOf() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_ALLOF_AND_DEFAULT_VALUE));

            String json = """
                {"citizenship": "FR"}""";
            String result = validator.validate(schema, json);
            assertThatJson(result).isEqualTo(
                """
                {"citizenship":"FR","status":"A"}"""
            );
        }

        @Test
        void should_inject_defaults_when_root_schema_is_a_ref() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_ROOT_REF));
            String result = validator.validate(schema, "{}");
            assertThatJson(result).isEqualTo(
                """
                {"timeout":5000}"""
            );
        }

        @Test
        void should_inject_defaults_in_deeply_nested_schema() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_DEEPLY_NESTED_DEFAULTS));

            String result = validator.validate(schema, "{}");
            assertThatJson(result).isEqualTo(
                """
                {"topField":"top-default","level1":{"level2":{"level3":{"value":"deep-default"}}}}
                """
            );
        }

        @Test
        void should_inject_defaults_at_deepest_level_when_parents_exist() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_DEEPLY_NESTED_DEFAULTS));

            String json = """
                {
                    "level1": {
                        "level2": {
                            "level3": {}
                        }
                    },
                    "topField": "provided"
                }
                """;
            String result = validator.validate(schema, json);
            assertThatJson(result).isEqualTo(
                """
                {
                  "topField":"provided",
                  "level1":{
                    "level2":{
                      "level3":{"value":"deep-default"}
                    }
                  }
                }
                """
            );
        }

        @Test
        void should_inject_optional_defaults_inside_array_elements() {
            String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "endpoints": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "url": { "type": "string" },
                          "weight": { "type": "integer", "default": 1 },
                          "backup": { "type": "boolean", "default": false }
                        }
                      }
                    }
                  }
                }
                """;

            String result = validator.validate(
                schema,
                """
                {"endpoints": [{"url": "http://a"}, {"url": "http://b", "weight": 5}]}"""
            );
            assertThatJson(result).isEqualTo(
                """
                {"endpoints":[{"url":"http://a","weight":1,"backup":false},{"url":"http://b","weight":5,"backup":false}]}"""
            );
        }

        @Test
        void should_inject_defaults_when_schema_uses_root_ref() {
            String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "name": { "type": "string" },
                    "enabled": { "type": "boolean", "default": true },
                    "child": { "$ref": "#" }
                  }
                }
                """;

            String result = validator.validate(
                schema,
                """
                {"name": "parent", "child": {"name": "nested"}}"""
            );
            assertThatJson(result).isEqualTo(
                """
                {"name":"parent","enabled":true,"child":{"name":"nested","enabled":true}}"""
            );
        }

        @Test
        void should_resolve_ref_with_escaped_json_pointer_tokens() {
            String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "config": { "$ref": "#/$defs/my~1config~0type" }
                  },
                  "$defs": {
                    "my/config~type": {
                      "type": "object",
                      "properties": {
                        "timeout": { "type": "integer", "default": 30 }
                      }
                    }
                  }
                }
                """;

            String result = validator.validate(
                schema,
                """
                {"config": {}}"""
            );
            assertThatJson(result).isEqualTo(
                """
                {"config":{"timeout":30}}"""
            );
        }

        @Test
        void should_clear_nulls_and_inject_defaults() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_DEFAULT_VALUE));

            String json = """
                {
                    "name": "John",
                    "age": null,
                    "citizenship": null
                }
                """;
            String result = validator.validate(schema, json);

            assertThatJson(result).isEqualTo(
                """
                {"citizenship":"USA","name":"John"}
                """
            );
        }
    }

    // -------------------------------------------------------------------------
    // Additional properties removal
    // -------------------------------------------------------------------------

    @Nested
    class AdditionalProperties {

        @Test
        void should_remove_additional_properties() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_NOT_ALLOWED_ADDITIONAL_PROPERTIES));

            String json = """
                { "name": "John", "age": 30, "unknown": 30 }""";
            String result = validator.validate(schema, json);
            assertThatJson(result).isEqualTo(
                """
                {"name":"John","age":30}"""
            );
        }

        @Test
        void should_remove_additional_properties_keeping_pattern_properties() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_NOT_ALLOWED_ADDITIONAL_PROPERTIES_AND_KEEP_PATTERN_PROPERTIES));

            String json = """
                { "name": "John", "to-keep": "value", "age": 30, "unknown": 30 }""";
            String result = validator.validate(schema, json);
            assertThatJson(result).isEqualTo(
                """
                {"name":"John","to-keep":"value","age":30}"""
            );
        }

        @Test
        void should_remove_additional_properties_in_nested_object() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_NESTED_ADDITIONAL_PROPERTIES));

            String json = """
                {
                    "name": "test",
                    "config": {
                        "timeout": 5000,
                        "retries": 3,
                        "unknownField": "should-be-removed"
                    }
                }
                """;
            String result = validator.validate(schema, json);
            assertThatJson(result).isEqualTo(
                """
                {"name":"test","config":{"retries":3,"timeout":5000}}
                """
            );
        }
    }

    // -------------------------------------------------------------------------
    // Custom regex validation
    // -------------------------------------------------------------------------

    @Nested
    class CustomRegex {

        @ParameterizedTest
        @ValueSource(strings = { "regex", "java-regex" })
        void should_accept_valid_regex(String regexFormat) throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_CUSTOM_REGEX)).replace("regex", regexFormat);
            String json = "{ \"name\": \"^.*[A-Za-z]\\\\d*$\", \"valid\": true }";
            String result = validator.validate(schema, json);
            assertThatJson(result).isEqualTo("{\"valid\":true,\"name\":\"^.*[A-Za-z]\\\\d*$\"}");
        }

        @ParameterizedTest
        @ValueSource(strings = { "regex", "java-regex" })
        void should_reject_invalid_regex(String regexFormat) throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_CUSTOM_REGEX)).replace("regex", regexFormat);

            assertThatThrownBy(() ->
                validator.validate(
                    schema,
                    """
                    { "name": "( INVALID regex", "valid": true }"""
                )
            )
                .isInstanceOf(InvalidJsonException.class)
                .hasMessage("$.name: does not match the %s pattern".formatted(regexFormat));
        }
    }

    // -------------------------------------------------------------------------
    // OneOf handling
    // -------------------------------------------------------------------------

    @Nested
    class OneOf {

        @Test
        void should_inject_defaults_with_oneOf() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_ONEOF_AND_DEFAULT_VALUE));

            String json = """
                {
                    "http": {
                        "keepAlive": true
                    }
                }
                """;
            String result = validator.validate(schema, json);
            assertThatJson(result).isEqualTo(
                """
                {"http":{"protocol":"HTTP1","keepAlive":true}}"""
            );
        }

        @Test
        void should_throw_when_oneOf_has_no_common_required_defaults() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_ONEOF_NO_COMMON_DEFAULT));

            assertThatThrownBy(() -> validator.validate(schema, "{}"))
                .isInstanceOf(InvalidJsonException.class)
                .hasMessage("$: must be valid to one and only one schema, but 0 are valid");
        }

        @Test
        void should_only_inject_common_required_defaults() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_ONEOF_PARTIAL_REQUIRED));

            String json = """
                { "onlyInFirst": "provided" }
                """;
            String result = validator.validate(schema, json);

            assertThatJson(result).isEqualTo(
                """
                {"common":"defaultCommon","onlyInFirst":"provided"}"""
            );
        }

        @Test
        void should_inject_defaults_when_allOf_wraps_oneOf() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_ALLOF_WRAPPING_ONEOF));

            String json = """
                { "http": { "version": "HTTP_1_1" } }
                """;
            String result = validator.validate(schema, json);

            assertThatJson(result).isEqualTo(
                """
                {"http":{"readTimeout":5000,"version":"HTTP_1_1"}}"""
            );
        }

        @Test
        void should_inject_defaults_when_ref_to_oneOf() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_REF_TO_ONEOF));

            String json = """
                { "http": { "connectTimeout": 5000 } }
                """;
            String result = validator.validate(schema, json);

            assertThatJson(result).isEqualTo(
                """
                {"http":{"connectTimeout":5000,"readTimeout":10000,"version":"HTTP_1_1"}}"""
            );
        }

        @Test
        void should_inject_defaults_when_ref_to_oneOf_with_second_subschema_discriminator() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_REF_TO_ONEOF));

            String json = """
                {
                    "http": {
                        "version": "HTTP_2"
                    }
                }
                """;
            String result = validator.validate(schema, json);

            assertThatJson(result).isEqualTo(
                """
                {"http":{"readTimeout":10000,"connectTimeout":3000,"version":"HTTP_2"}}
                """
            );
        }

        @Test
        void should_default_to_first_subschema_when_multiple_oneOf_match() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_ONEOF_MULTIPLE_MATCH));

            String json = """
                { "config": { "timeout": 5000 } }
                """;
            String result = validator.validate(schema, json);

            assertThatJson(result).isEqualTo(
                """
                {"config":{"timeout":5000,"type":"TYPE_A"}}"""
            );
        }

        @Test
        void should_inject_root_default_when_discriminator_matches_subschema() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_ONEOF_REQUIRED_WITH_ROOT_DEFAULTS));
            String json = """
                { "partyType": "NATURAL" }""";
            String result = validator.validate(schema, json);
            assertThatJson(result).isEqualTo(
                """
                {"name":"ACME","partyType":"NATURAL"}"""
            );
        }

        @Test
        void should_inject_root_defaults_when_input_empty() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_WITH_ONEOF_REQUIRED_WITH_ROOT_DEFAULTS));
            String json = "{\n}";
            String result = validator.validate(schema, json);
            assertThatJson(result).isEqualTo(
                """
                {"name":"ACME","partyType":"COMPANY"}"""
            );
        }

        @Test
        void should_preserve_pattern_properties_in_oneOf_subschema() throws IOException {
            String schema = Files.readString(Path.of(SCHEMA_ONEOF_PATTERN_PROPERTIES));

            String json = """
                {
                    "endpoint": {
                        "x-custom-header": "value"
                    }
                }
                """;
            String result = validator.validate(schema, json);

            assertThatJson(result).isEqualTo(
                """
                {"endpoint": {"type":"HTTP","x-custom-header":"value"}}"""
            );
        }

        @Nested
        class DiscriminatorWithRequiredFields {

            @Test
            void should_choose_first_subschema_and_inject_required_defaults_when_no_discriminator() throws IOException {
                String schema = Files.readString(Path.of(SCHEMA_ONEOF_DISCRIMINATOR_WITH_REQUIRED));

                String json = """
                    {
                        "endpoint": {}
                    }
                    """;
                String result = validator.validate(schema, json);

                assertThatJson(result).isEqualTo(
                    """
                    {"endpoint": {"keepAliveTimeout":30000,"readTimeout":10000,"connectTimeout":5000,"type":"HTTP"}}"""
                );
            }

            @Test
            void should_inject_required_defaults_when_first_subschema_discriminator_provided() throws IOException {
                String schema = Files.readString(Path.of(SCHEMA_ONEOF_DISCRIMINATOR_WITH_REQUIRED));

                String json = """
                    {
                        "endpoint": {
                            "type": "HTTP"
                        }
                    }
                    """;
                String result = validator.validate(schema, json);

                assertThatJson(result).isEqualTo(
                    """
                    {"endpoint": {"keepAliveTimeout":30000,"readTimeout":10000,"connectTimeout":5000,"type":"HTTP"}}"""
                );
            }

            @Test
            void should_inject_required_defaults_when_second_subschema_discriminator_provided() throws IOException {
                String schema = Files.readString(Path.of(SCHEMA_ONEOF_DISCRIMINATOR_WITH_REQUIRED));

                String json = """
                    {
                        "endpoint": {
                            "type": "GRPC"
                        }
                    }
                    """;
                String result = validator.validate(schema, json);

                assertThatJson(result).isEqualTo(
                    """
                    {"endpoint": {"port":443,"readTimeout":5000,"connectTimeout":3000,"type":"GRPC"}}"""
                );
            }
        }

        @Nested
        class SubschemaFallback {

            @Test
            void should_pick_first_subschema_when_oneOf_has_no_discriminator() throws IOException {
                String schema = Files.readString(Path.of(SCHEMA_ONEOF_NO_DISCRIMINATOR));

                String json = """
                    {
                        "endpoint": {}
                    }
                    """;
                String result = validator.validate(schema, json);

                assertThatJson(result).isEqualTo(
                    """
                    {"endpoint": {"host":"localhost","port":8080}}"""
                );
            }

            @Test
            void should_throw_when_discriminator_matches_no_branch() throws IOException {
                String schema = Files.readString(Path.of(SCHEMA_ONEOF_DISCRIMINATOR_WITH_REQUIRED));

                String json = """
                    {
                        "endpoint": {
                            "type": "UNKNOWN"
                        }
                    }
                    """;

                assertThatThrownBy(() -> validator.validate(schema, json))
                    .isInstanceOf(InvalidJsonException.class)
                    .hasMessageContaining("must be valid to one and only one schema");
            }

            @Test
            void should_match_freeform_branch_when_const_branch_does_not_match() throws IOException {
                String schema = Files.readString(Path.of(SCHEMA_ONEOF_MIXED_CONST_FREEFORM));

                String json = """
                    {
                        "endpoint": {
                            "mode": "CUSTOM"
                        }
                    }
                    """;
                String result = validator.validate(schema, json);

                assertThatJson(result).isEqualTo(
                    """
                    {"endpoint": {"mode":"CUSTOM","label":"free"}}"""
                );
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cache behavior
    // -------------------------------------------------------------------------

    @Nested
    class CacheBehavior {

        @Test
        void should_cache_schema_once_for_repeated_validation() throws IOException {
            String schema = Files.readString(Path.of(SIMPLE_SCHEMA));
            String json = """
                { "name": "John", "age": 30 }""";

            assertThat(cacheSize()).isZero();

            validator.validate(schema, json);
            assertThat(cacheSize()).isOne();

            validator.validate(schema, json);
            assertThat(cacheSize()).isOne();
        }

        @Test
        void should_cache_two_entries_for_two_different_schemas() throws IOException {
            String schema1 = Files.readString(Path.of(SIMPLE_SCHEMA));
            String schema2 = Files.readString(Path.of(SCHEMA_WITH_DEFAULT_VALUE));
            String json = """
                { "name": "John", "age": 30 }""";

            validator.validate(schema1, json);
            assertThat(cacheSize()).isOne();

            validator.validate(schema2, json);
            assertThat(cacheSize()).isEqualTo(2);
        }

        @Test
        void should_not_cache_when_schema_is_empty_or_null() {
            validator.validate("", "{ } ");
            assertThat(cacheSize()).isZero();

            validator.validate(null, "{ } ");
            assertThat(cacheSize()).isZero();
        }

        @Test
        void should_cache_once_under_concurrency() throws Exception {
            String schema = Files.readString(Path.of(SIMPLE_SCHEMA));
            String json = """
                { "name": "John", "age": 30 }""";

            int threads = 8;
            ExecutorService es = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            java.util.concurrent.atomic.AtomicReference<Throwable> errorReference = new java.util.concurrent.atomic.AtomicReference<>();

            for (int i = 0; i < threads; i++) {
                es.submit(() -> {
                    try {
                        start.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        validator.validate(schema, json);
                    } catch (Throwable e) {
                        errorReference.set(e);
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            boolean finished = done.await(10, java.util.concurrent.TimeUnit.SECONDS);
            es.shutdownNow();

            assertThat(finished).as("Test threads timed out").isTrue();
            assertThat(errorReference.get()).as("Validation failed in a concurrent thread").isNull();
            assertThat(cacheSize()).isOne();
        }
    }

    // -------------------------------------------------------------------------
    // Regression tests
    // -------------------------------------------------------------------------

    @Nested
    class Regressions {

        @Test
        void should_not_stack_overflow_on_circular_ref() {
            String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "required": ["foo"],
                  "properties": {
                    "foo": { "$ref": "#/$defs/A" }
                  },
                  "$defs": {
                    "A": { "$ref": "#/$defs/B" },
                    "B": { "$ref": "#/$defs/A" }
                  }
                }
                """;

            assertThatThrownBy(() -> validator.validate(schema, "{}"))
                .isInstanceOf(InvalidJsonException.class)
                .hasMessageContaining("required property 'foo' not found");
        }

        @Test
        void should_inject_default_in_array_element_when_required_field_missing() {
            String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "items": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["status"],
                        "properties": {
                          "status": { "type": "string", "default": "active" }
                        }
                      }
                    }
                  }
                }
                """;

            String result = validator.validate(schema, "{\"items\": [{}]}");
            assertThatJson(result).isEqualTo(
                """
                {"items":[{"status":"active"}]}"""
            );
        }

        @Test
        void should_inject_default_in_nested_array_element() {
            String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "config": {
                      "type": "object",
                      "properties": {
                        "rules": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "required": ["priority"],
                            "properties": {
                              "priority": { "type": "integer", "default": 0 },
                              "name": { "type": "string" }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

            String result = validator.validate(
                schema,
                """
                {"config": {"rules": [{"name": "rule1"}, {"name": "rule2"}]}}"""
            );
            assertThatJson(result).isEqualTo(
                """
                {"config":{"rules":[{"priority":0,"name":"rule1"},{"priority":0,"name":"rule2"}]}}"""
            );
        }

        @Test
        void should_resolve_required_default_when_property_name_contains_slash() {
            String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "a/b": {
                      "type": "object",
                      "required": ["status"],
                      "properties": {
                        "status": { "type": "string", "default": "active" }
                      }
                    }
                  }
                }
                """;

            String result = validator.validate(
                schema,
                """
                {"a/b": {}}"""
            );
            assertThatJson(result).isEqualTo(
                """
                {"a/b":{"status":"active"}}"""
            );
        }

        @Test
        void should_not_swallow_sibling_error_whose_path_shares_a_oneOf_prefix() {
            String schema = """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "type": "object",
                  "properties": {
                    "http": {
                      "type": "object",
                      "oneOf": [
                        { "required": ["type"], "properties": { "type": { "const": "HTTP1" } } },
                        { "required": ["type"], "properties": { "type": { "const": "HTTP2" } } }
                      ]
                    },
                    "httpProxy": {
                      "type": "object",
                      "properties": { "mode": { "const": "SYSTEM" } }
                    }
                  }
                }
                """;

            assertThatThrownBy(() ->
                validator.validate(
                    schema,
                    """
                    {"http": {}, "httpProxy": {"mode": "WRONG"}}"""
                )
            )
                .isInstanceOf(InvalidJsonException.class)
                .hasMessageContaining("$.httpProxy.mode");
        }
    }
}
