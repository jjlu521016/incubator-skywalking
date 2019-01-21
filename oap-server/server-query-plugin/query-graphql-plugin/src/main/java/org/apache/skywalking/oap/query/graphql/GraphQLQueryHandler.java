/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.query.graphql;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.skywalking.oap.query.graphql.jwt.AuthResultData;
import org.apache.skywalking.oap.query.graphql.jwt.AuthUtil;
import org.apache.skywalking.oap.query.graphql.jwt.config.JwtConfig;
import org.apache.skywalking.oap.query.graphql.jwt.config.SwAuthConfig;
import org.apache.skywalking.oap.server.library.server.jetty.JettyJsonHandler;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;
import org.apache.skywalking.oap.server.library.util.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RequiredArgsConstructor
public class GraphQLQueryHandler extends JettyJsonHandler {

    private static final Logger logger = LoggerFactory.getLogger(GraphQLQueryHandler.class);

    private static final String QUERY = "query";
    private static final String VARIABLES = "variables";
    private static final String MESSAGE = "message";

    private final Gson gson = new Gson();

    private final String path;

    private final GraphQL graphQL;

    private static final JwtConfig JWTCONFIG;
    private static final SwAuthConfig SWAUTHCONFIG;

    /**
     * init auth info
     */
    static {
        Properties properties = new Properties();
        JWTCONFIG = new JwtConfig();
        SWAUTHCONFIG = new SwAuthConfig();
        try {
            properties.load(ResourceUtils.read("swauth.properties"));
            // jwt info
            JWTCONFIG.setClientId(properties.getProperty("jwt.clientId"));
            JWTCONFIG.setBase64Secret(properties.getProperty("jwt.base64Secret"));
            JWTCONFIG.setName(properties.getProperty("jwt.name"));
            JWTCONFIG.setExpiresMillisSecond(properties.getProperty("jwt.expiresMillisSecond"));
            // auth info
            SWAUTHCONFIG.setUserName(properties.getProperty("swauth.userName"));
            SWAUTHCONFIG.setPassword(properties.getProperty("swauth.password"));
            logger.info("swauth init finished!---");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String pathSpec() {
        return path;
    }

    @Override
    protected JsonElement doGet(HttpServletRequest req) {
        throw new UnsupportedOperationException("GraphQL only supports POST method");
    }

    @Override
    protected JsonElement doPost(HttpServletRequest req) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(req.getInputStream()));
        String line;
        StringBuilder request = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            request.append(line);
        }

        JsonObject requestJson = gson.fromJson(request.toString(), JsonObject.class);
        if (null == requestJson.get(QUERY)) {
            return execute(req, requestJson.toString(), null);
        }
        return execute(req, requestJson.get(QUERY).getAsString(), gson.fromJson(requestJson.get(VARIABLES), new TypeToken<Map<String, Object>>() {
        }.getType()));
    }

    private JsonObject execute(HttpServletRequest req, String request, Map<String, Object> variables) {
        try {
            // auth check
            AuthResultData authResultData = AuthUtil.authJwt(req, request, JWTCONFIG, SWAUTHCONFIG);
            if (!authResultData.isAuthSuccess()) {
                return authResultData.getData();
            }
            ExecutionInput executionInput = ExecutionInput.newExecutionInput().query(request).variables(variables).build();
            ExecutionResult executionResult = graphQL.execute(executionInput);
            logger.debug("Execution result is {}", executionResult);
            Object data = executionResult.getData();
            List<GraphQLError> errors = executionResult.getErrors();

            JsonArray errorArray = new JsonArray();
            if (CollectionUtils.isNotEmpty(errors)) {
                errors.forEach(error -> {
                    JsonObject errorJson = new JsonObject();
                    errorJson.addProperty(MESSAGE, error.getMessage());
                    errorArray.add(errorJson);
                });
            }
            return AuthUtil.returnJson(data, HttpServletResponse.SC_OK, errorArray);
        } catch (final Throwable e) {
            logger.error(e.getMessage(), e);
            JsonArray errorArray = new JsonArray();
            JsonObject errorJson = new JsonObject();
            errorJson.addProperty(MESSAGE, e.getMessage());
            errorArray.add(errorJson);
            return AuthUtil.returnJson(null, HttpServletResponse.SC_OK, errorArray);
        }
    }

}
