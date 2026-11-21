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

import java.util.Set;

public record AgentToolPrincipal(
        String userId,
        String tenantId,
        Set<String> roles,
        Set<String> allowedKnowledgeBaseIds,
        Set<String> toolScopes
) {

    public AgentToolPrincipal {
        roles = immutable(roles);
        allowedKnowledgeBaseIds = immutable(allowedKnowledgeBaseIds);
        toolScopes = immutable(toolScopes);
    }

    private static Set<String> immutable(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
