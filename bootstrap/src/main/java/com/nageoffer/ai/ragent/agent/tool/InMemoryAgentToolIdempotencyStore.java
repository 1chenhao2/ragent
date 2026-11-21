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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

class InMemoryAgentToolIdempotencyStore implements AgentToolIdempotencyStore {

    private final Map<InvocationKey, StoredInvocation> invocations = new ConcurrentHashMap<>();

    @Override
    public synchronized AgentToolResult execute(
            AgentToolPrincipal principal,
            AgentToolCommand command,
            Supplier<AgentToolResult> invocation
    ) {
        InvocationKey key = new InvocationKey(principal.tenantId(), principal.userId(), command.idempotencyKey());
        StoredInvocation stored = invocations.get(key);
        InvocationPayload payload = new InvocationPayload(command.toolName(), command.arguments());
        if (stored != null && stored.payload().equals(payload)) {
            return stored.result();
        }
        if (stored != null) {
            throw new AgentToolException(409, "idempotency_conflict", "Idempotency key was used with another payload");
        }
        AgentToolResult result = invocation.get();
        invocations.put(key, new StoredInvocation(payload, result));
        return result;
    }

    private record InvocationKey(String tenantId, String userId, String idempotencyKey) {
    }

    private record InvocationPayload(String toolName, Map<String, Object> arguments) {
    }

    private record StoredInvocation(InvocationPayload payload, AgentToolResult result) {
    }
}
