/*
 * Copyright (c) 2015. Rick Hightower, Geoff Chandler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * QBit - The Microservice lib for Java : JSON, WebSocket, REST. Be The Web!
 */

package io.advantageous.qbit.transforms;

import io.advantageous.qbit.json.JsonMapper;
import io.advantageous.qbit.message.Response;

/**
 * Transforms raw json into a client response object.
 * <p>
 * created by Richard on 8/26/14.
 *
 * @author rhightower
 */
public class JsonResponseTransformer implements Transformer<Response<Object>, Response> {

    private final JsonMapper mapper;

    public JsonResponseTransformer(final JsonMapper mapper) {
        this.mapper = mapper;
    }

    protected String toJson(final Object object) {
        return mapper.toJson(object);
    }

    @Override
    public Response transform(final Response<Object> response) {
        final Object body = toJson(response.body());
        response.body(body);
        return response;
    }
}
