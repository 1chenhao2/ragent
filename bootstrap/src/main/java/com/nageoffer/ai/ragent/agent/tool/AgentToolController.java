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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/agent-tools")
@ConditionalOnProperty(name = "ragent.agent.runtime", havingValue = "python")
public class AgentToolController {

    private final AgentToolTokenVerifier tokenVerifier;
    private final AgentToolGateway toolGateway;

    public AgentToolController(AgentToolTokenVerifier tokenVerifier, AgentToolGateway toolGateway) {
        this.tokenVerifier = tokenVerifier;
        this.toolGateway = toolGateway;
    }

    @PostMapping("/invoke")
    public AgentToolResult invoke(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody AgentToolCommand command
    ) {
        AgentToolPrincipal principal = tokenVerifier.verify(authorization);
        return toolGateway.invoke(principal, command);
    }
}
