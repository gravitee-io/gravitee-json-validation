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
package io.gravitee.json.validation.helper;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JsonHelper {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    private JsonHelper() {}

    public static String clearNullValues(String jsonPayload) {
        if (jsonPayload == null) {
            return null;
        }

        try {
            // #4087 - ugly fix to remove null entries in the PolicyConfiguration
            // otherwise updating the API is impossible.
            Object staleObject = objectMapper.readValue(jsonPayload, Object.class);
            return objectMapper.writeValueAsString(staleObject);
        } catch (IOException e) {
            log.debug("Unable to remove 'null' values from json configuration, return the original value", e);
            return jsonPayload;
        }
    }
}
