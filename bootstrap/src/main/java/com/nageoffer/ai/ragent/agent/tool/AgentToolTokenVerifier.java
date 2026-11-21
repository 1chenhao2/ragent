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
 */

package com.nageoffer.ai.ragent.agent.tool;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.nageoffer.ai.ragent.agent.python.PythonAgentProperties;

import java.util.List;
import java.util.Set;

public class AgentToolTokenVerifier {

    private final JWTVerifier verifier;

    public AgentToolTokenVerifier(PythonAgentProperties properties) {
        PythonAgentProperties.DelegatedToken token = properties.getDelegatedToken();
        if (token.getSecret() == null || token.getSecret().length() < 32) {
            throw new IllegalStateException("ragent.agent.python.delegated-token.secret must contain at least 32 characters");
        }
        this.verifier = JWT.require(Algorithm.HMAC256(token.getSecret()))
                .withIssuer(token.getIssuer())
                .withAudience(token.getBusinessToolAudience())
                .build();
    }

    public AgentToolPrincipal verify(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw unauthorized("Missing delegated Agent token");
        }
        String token = authorization.substring("Bearer ".length());
        if (token.isBlank()) {
            throw unauthorized("Missing delegated Agent token");
        }

        try {
            DecodedJWT jwt = verifier.verify(token);
            String userId = jwt.getSubject();
            if (userId == null || userId.isBlank()) {
                throw unauthorized("Delegated Agent token has no subject");
            }
            return new AgentToolPrincipal(
                    userId,
                    jwt.getClaim("tenantId").asString(),
                    requiredSet(jwt, "roles"),
                    requiredSet(jwt, "allowedKnowledgeBaseIds"),
                    requiredSet(jwt, "toolScopes")
            );
        } catch (JWTVerificationException | IllegalArgumentException ex) {
            throw unauthorized("Invalid delegated Agent token");
        }
    }

    private Set<String> requiredSet(DecodedJWT jwt, String claimName) {
        List<String> values = jwt.getClaim(claimName).asList(String.class);
        if (values == null || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw unauthorized("Invalid delegated Agent token claim: " + claimName);
        }
        return Set.copyOf(values);
    }

    private AgentToolException unauthorized(String message) {
        return new AgentToolException(401, "invalid_delegated_token", message);
    }
}
