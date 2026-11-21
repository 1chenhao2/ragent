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

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class AuthorizedAgentToolGateway implements AgentToolGateway {

    private final Map<String, AgentTool> tools;
    private final AgentToolIdempotencyStore idempotencyStore;

    public AuthorizedAgentToolGateway(List<AgentTool> tools) {
        this(tools, new InMemoryAgentToolIdempotencyStore());
    }

    AuthorizedAgentToolGateway(List<AgentTool> tools, AgentToolIdempotencyStore idempotencyStore) {
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(AgentTool::name, Function.identity()));
        this.idempotencyStore = idempotencyStore;
    }

    @Override
    public AgentToolResult invoke(AgentToolPrincipal principal, AgentToolCommand command) {
        validateCommand(command);
        AgentTool tool = tools.get(command.toolName());
        if (tool == null) {
            throw new AgentToolException(404, "tool_not_found", "Unknown Agent Tool: " + command.toolName());
        }
        if (!principal.toolScopes().containsAll(tool.requiredScopes())) {
            throw new AgentToolException(403, "tool_scope_forbidden", "Missing required Agent Tool scope");
        }
        return idempotencyStore.execute(principal, command, () -> invoke(tool, principal, command));
    }

    private void validateCommand(AgentToolCommand command) {
        if (command == null
                || command.toolName() == null
                || command.toolName().isBlank()
                || command.toolCallId() == null
                || command.toolCallId().isBlank()
                || command.idempotencyKey() == null
                || command.idempotencyKey().length() < 16
                || command.idempotencyKey().length() > 128) {
            throw new AgentToolException(400, "invalid_tool_command", "Agent Tool command is invalid");
        }
    }

    private AgentToolResult invoke(AgentTool tool, AgentToolPrincipal principal, AgentToolCommand command) {
        Map<String, Object> output = tool.invoke(principal, command.arguments());
        return new AgentToolResult(command.toolCallId(), command.toolName(), output);
    }
}
