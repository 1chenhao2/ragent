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

package com.nageoffer.ai.ragent.agent.gateway;

import java.util.Set;

/**
 * Access delegated to an Agent run.
 */
public record AgentAccessContext(
        String tenantId,
        Set<String> roles,
        Set<String> allowedKnowledgeBaseIds,
        Set<String> toolScopes
) {

    public AgentAccessContext {
        roles = immutable(roles);
        allowedKnowledgeBaseIds = immutable(allowedKnowledgeBaseIds);
        toolScopes = immutable(toolScopes);
    }

    /**
     * Creates an empty, deny-by-default access context.
     */
    public static AgentAccessContext denied() {
        return new AgentAccessContext(null, Set.of(), Set.of(), Set.of());
    }

    private static Set<String> immutable(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
