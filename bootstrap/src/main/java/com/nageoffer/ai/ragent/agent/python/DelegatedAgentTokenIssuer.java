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

package com.nageoffer.ai.ragent.agent.python;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues short-lived identity tokens for the Python Agent service.
 */
@Component
@ConditionalOnProperty(name = "ragent.agent.runtime", havingValue = "python")
public class DelegatedAgentTokenIssuer {

    private final Algorithm algorithm;
    private final String issuer;
    private final String audience;
    private final String businessToolAudience;
    private final Duration ttl;
    private final Clock clock;

    public DelegatedAgentTokenIssuer(PythonAgentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    DelegatedAgentTokenIssuer(PythonAgentProperties properties, Clock clock) {
        PythonAgentProperties.DelegatedToken token = properties.getDelegatedToken();
        if (token.getSecret() == null || token.getSecret().length() < 32) {
            throw new IllegalStateException("ragent.agent.python.delegated-token.secret must contain at least 32 characters");
        }
        if (token.getTtl() == null || token.getTtl().isNegative() || token.getTtl().isZero()) {
            throw new IllegalStateException("ragent.agent.python.delegated-token.ttl must be positive");
        }
        this.algorithm = Algorithm.HMAC256(token.getSecret());
        this.issuer = token.getIssuer();
        this.audience = token.getAudience();
        this.businessToolAudience = token.getBusinessToolAudience();
        this.ttl = token.getTtl();
        this.clock = clock;
    }

    /**
     * Issues a token whose claims exactly match the run access context.
     */
    public String issue(AgentRunCommand command) {
        return issue(command.userId(), command.access());
    }

    /**
     * Issues a delegated token for a non-run Agent computation such as ingestion.
     */
    public String issue(String userId, AgentAccessContext access) {
        Instant issuedAt = clock.instant();
        JWTCreator.Builder builder = JWT.create()
                .withSubject(userId)
                .withIssuer(issuer)
                .withAudience(audience, businessToolAudience)
                .withIssuedAt(Date.from(issuedAt))
                .withExpiresAt(Date.from(issuedAt.plus(ttl)))
                .withArrayClaim("roles", sorted(access.roles()))
                .withArrayClaim("allowedKnowledgeBaseIds", sorted(access.allowedKnowledgeBaseIds()))
                .withArrayClaim("toolScopes", sorted(access.toolScopes()));
        if (access.tenantId() != null) {
            builder.withClaim("tenantId", access.tenantId());
        }
        return builder.sign(algorithm);
    }

    private String[] sorted(java.util.Set<String> values) {
        return values.stream().sorted().toArray(String[]::new);
    }
}
