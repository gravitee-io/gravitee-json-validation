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

import org.everit.json.schema.ValidationException;

public class InvalidJsonException extends RuntimeException {

    public InvalidJsonException(ValidationException validationException) {
        this(getAllMessages(validationException));
    }

    public InvalidJsonException(String message) {
        super(message);
    }

    private static String getAllMessages(ValidationException validationException) {
        return String.join("\n", validationException.getAllMessages());
    }
}
