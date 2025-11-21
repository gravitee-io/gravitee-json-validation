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

import com.github.benmanes.caffeine.cache.Cache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.everit.json.schema.Schema;
import org.junit.jupiter.api.BeforeEach;
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

    JsonSchemaValidator validator = new JsonSchemaValidatorImpl();

    @BeforeEach
    void setUp() {
        clearCache();
    }

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
    void should_return_updated_json_required_default_value_with_allOf() throws IOException {
        String schema = Files.readString(Path.of(SCHEMA_WITH_ALLOF_AND_DEFAULT_VALUE));

        String json = "{\"citizenship\": \"FR\"}";
        String result = validator.validate(schema, json);
        assertThat(result).isEqualTo("{\"citizenship\":\"FR\",\"status\":\"A\"}");
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

    // ---------------- Cache behavior tests ----------------

    @Test
    void should_cache_schema_once_for_repeated_validation() throws Exception {
        String schema = Files.readString(Path.of(SIMPLE_SCHEMA));
        String json = "{\n" + "  \"name\": \"John\",\n" + "  \"age\": 30\n" + "}";

        assertThat(cacheSize()).isZero();

        // First validation builds and caches the schema
        validator.validate(schema, json);
        assertThat(cacheSize()).isEqualTo(1);

        // Second validation with same schema should reuse cache (still 1)
        validator.validate(schema, json);
        assertThat(cacheSize()).isEqualTo(1);
    }

    @Test
    void should_cache_two_entries_for_two_different_schemas() throws Exception {
        String schema1 = Files.readString(Path.of(SIMPLE_SCHEMA));
        String schema2 = Files.readString(Path.of(SCHEMA_WITH_DEFAULT_VALUE));
        String json = "{\n" + "  \"name\": \"John\",\n" + "  \"age\": 30\n" + "}";

        validator.validate(schema1, json);
        assertThat(cacheSize()).isEqualTo(1);

        validator.validate(schema2, json);
        assertThat(cacheSize()).isEqualTo(2);
    }

    @Test
    void should_not_cache_when_schema_is_empty_or_null() {
        // Empty schema string
        validator.validate("", "{ } ");
        assertThat(cacheSize()).isZero();

        // Null schema
        validator.validate(null, "{ } ");
        assertThat(cacheSize()).isZero();
    }

    @Test
    void should_cache_once_under_concurrency() throws Exception {
        String schema = Files.readString(Path.of(SIMPLE_SCHEMA));
        String json = "{\n" + "  \"name\": \"John\",\n" + "  \"age\": 30\n" + "}";

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
                } catch (Throwable e) { // Catch Throwable to handle Errors too, not just Exceptions
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
        assertThat(cacheSize()).isEqualTo(1);
    }
}
