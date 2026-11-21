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
import com.auth0.jwt.algorithms.Algorithm;
import com.nageoffer.ai.ragent.agent.python.PythonAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolTokenVerifierTest {

    private static final String SECRET = "test-delegated-token-secret-0123456789";

    private AgentToolTokenVerifier tokenVerifier;

    @BeforeEach
    void setUp() {
        PythonAgentProperties properties = new PythonAgentProperties();
        properties.getDelegatedToken().setSecret(SECRET);
        tokenVerifier = new AgentToolTokenVerifier(properties);
    }

    @Test
    void shouldVerifyBusinessToolAudienceAndBuildDelegatedPrincipal() {
        String token = token("ragent-agent-service", "ragent-business-tools");

        AgentToolPrincipal principal = tokenVerifier.verify("Bearer " + token);

        assertEquals("user-1", principal.userId());
        assertEquals("tenant-1", principal.tenantId());
        assertEquals(Set.of("user"), principal.roles());
        assertEquals(Set.of("knowledge-1"), principal.allowedKnowledgeBaseIds());
        assertEquals(Set.of("knowledge:read"), principal.toolScopes());
    }

    @Test
    void shouldRejectTokenWithoutBusinessToolAudience() {
        String token = token("ragent-agent-service");

        AgentToolException exception = assertThrows(
                AgentToolException.class,
                () -> tokenVerifier.verify("Bearer " + token)
        );

        assertEquals(401, exception.status());
        assertEquals("invalid_delegated_token", exception.code());
    }

    private String token(String... audiences) {
        Instant now = Instant.now();
        return JWT.create()
                .withSubject("user-1")
                .withIssuer("ragent-java")
                .withAudience(audiences)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plusSeconds(300)))
                .withClaim("tenantId", "tenant-1")
                .withArrayClaim("roles", new String[]{"user"})
                .withArrayClaim("allowedKnowledgeBaseIds", new String[]{"knowledge-1"})
                .withArrayClaim("toolScopes", new String[]{"knowledge:read"})
                .sign(Algorithm.HMAC256(SECRET));
    }
}
