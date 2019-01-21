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
package org.apache.skywalking.oap.query.graphql.jwt;

import com.alibaba.fastjson.JSON;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.jsonwebtoken.Claims;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.apache.skywalking.apm.util.StringUtil;
import org.apache.skywalking.oap.query.graphql.jwt.config.JwtConfig;
import org.apache.skywalking.oap.query.graphql.jwt.config.SwAuthConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author jjlu521016@gmail.com
 */
public class AuthUtil {
    private static final Logger logger = LoggerFactory.getLogger(AuthUtil.class);
    private static final String DATA = "data";
    private static final String ERRORS = "errors";
    private static final String MESSAGE = "message";
    private static final String BIZ_CODE = "biz_code";
    private static final String STATUS = "status";
    private static final Gson GSON = new Gson();

    /**
     * jwt
     *
     * @param request
     */
    public static AuthResultData authJwt(HttpServletRequest request, String body, JwtConfig jwtConfig,
        SwAuthConfig swAuthConfig) {
        final String apmUrl = request.getHeader("apmurl");
        String uri = request.getRequestURI();

        if ("/api/check".equals(apmUrl) || "/agent/gRPC".equals(uri)) {
            logger.debug("checl url are request");
            return new AuthResultData(true, null);
        } else if ("/api/login/account".equals(apmUrl)) {
            return loginCheck(body, jwtConfig, swAuthConfig);
        } else {
            // authorization
            final String authHeader = request.getHeader("authorization");
            try {
                if (authHeader == null || !authHeader.startsWith("bearer;")) {
                    logger.error("[auth fail] no token!");
                    return new AuthResultData(false, returnJson(null, HttpServletResponse.SC_UNAUTHORIZED, buildErrorMsg("no token info")));
                }
                final String token = authHeader.substring(7);
                if (StringUtil.isEmpty(token)) {
                    logger.error("[auth fail] token is empty!");
                    return new AuthResultData(false, returnJson(null, HttpServletResponse.SC_UNAUTHORIZED, buildErrorMsg("no token info")));
                }
                final Claims claims = JwtHelper.parseJWT(token, jwtConfig.getBase64Secret());
                if (claims == null) {
                    logger.error("[auth fail] token is error");
                    // not auth
                    return new AuthResultData(false, returnJson(null, HttpServletResponse.SC_UNAUTHORIZED, buildErrorMsg("no token info")));
                }
                Integer expire = (Integer)claims.get("exp");
                long currentSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.of("+8"));
                if (Long.valueOf(expire) < currentSecond) {
                    return new AuthResultData(false, returnJson(null, HttpServletResponse.SC_UNAUTHORIZED, buildErrorMsg("no token info")));
                }
            } catch (final Exception e) {
                return new AuthResultData(false, returnJson(null, HttpServletResponse.SC_BAD_REQUEST, buildErrorMsg(e.getMessage())));

            }
            return new AuthResultData(true, null);
        }

    }

    private static AuthResultData loginCheck(String body, JwtConfig jwtConfig, SwAuthConfig swAuthConfig) {
        if (StringUtil.isEmpty(body)) {
            // return 403
            return new AuthResultData(false, returnJson(null, HttpServletResponse.SC_FORBIDDEN, buildErrorMsg("userName or password error!")));
        }
        AdminUser adminUser = JSON.parseObject(body, AdminUser.class);
        if (swAuthConfig.getUserName().equals(adminUser.getUserName()) && swAuthConfig.getPassword().equals(adminUser.getPassword())) {
            String jwtToken = JwtHelper.createJWT(swAuthConfig.getUserName(),
                jwtConfig.getName(),
                jwtConfig.getClientId(),
                Long.valueOf(jwtConfig.getExpiresMillisSecond()),
                jwtConfig.getBase64Secret());
            return new AuthResultData(false, returnJson("bearer;" + jwtToken, HttpServletResponse.SC_OK, null));
        } else {
            // return 403
            return new AuthResultData(false, returnJson(null, HttpServletResponse.SC_FORBIDDEN, buildErrorMsg("userName or password error!")));
        }

    }

    /**
     * build json data for httpResponse
     *
     * @param data
     * @param code
     * @param errorArray
     * @return
     */
    public static JsonObject returnJson(Object data, int code, JsonArray errorArray) {
        JsonObject jsonObject = new JsonObject();
        if (data != null) {
            if (data instanceof String) {
                jsonObject.addProperty(DATA, (String)data);
            } else {
                jsonObject.add(DATA, GSON.fromJson(GSON.toJson(data), JsonObject.class));
            }
        }
        jsonObject.add(ERRORS, errorArray);
        jsonObject.addProperty(BIZ_CODE, code);
        jsonObject.addProperty(STATUS, "ok");
        return jsonObject;
    }

    /**
     * build error message httpResponse
     *
     * @param message
     * @return
     */
    private static JsonArray buildErrorMsg(String message) {
        JsonArray errorArray = new JsonArray();
        JsonObject errorJson = new JsonObject();
        errorJson.addProperty(MESSAGE, message);
        errorArray.add(errorJson);
        return errorArray;
    }
}
