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

public interface JsonSchemaValidator {
    /**
     * Validates {@code json} against the given JSON {@code schema} and returns the JSON with recovery
     * mutations applied: optional defaults are injected and properties that violate the schema
     * (e.g. unexpected {@code additionalProperties}) are removed where possible. When {@code schema}
     * is {@code null} or empty, the input is returned with only {@code null} values stripped.
     *
     * <p><strong>Limitation:</strong> the recovery mutations only follow <em>local</em> {@code $ref}s
     * (JSON Pointers of the form {@code #/...}). External refs ({@code http://}, {@code file://}) and
     * relative refs are left unresolved, so subschemas reachable only through such refs are not visited
     * for default injection or property pruning. (Core pass/fail validation itself does resolve refs
     * via the underlying validator; this limitation applies to the recovery step only.)
     *
     * @param schema the JSON schema as a string; may be {@code null} or empty to skip validation
     * @param json   the JSON document to validate
     * @return the (possibly mutated) JSON document serialized as a string
     * @throws InvalidJsonException if {@code schema} or {@code json} cannot be parsed as JSON
     */
    String validate(String schema, String json);
}
