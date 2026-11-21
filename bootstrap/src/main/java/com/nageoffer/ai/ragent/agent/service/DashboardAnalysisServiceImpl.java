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

package com.nageoffer.ai.ragent.agent.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.adapter.sse.SseAgentEventPublisher;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessResolver;
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Java control-plane entry point for the read-only dashboard analysis Agent.
 */
@Service
@RequiredArgsConstructor
public class DashboardAnalysisServiceImpl implements DashboardAnalysisService {

    private static final String DASHBOARD_READ_SCOPE = "dashboard:read";

    private final AgentGateway agentGateway;
    private final AgentAccessResolver agentAccessResolver;

    @Override
    public void streamAnalysis(String question, String conversationId, boolean deepThinking, SseEmitter emitter) {
        LoginUser user = UserContext.requireUser();
        AgentAccessContext access = agentAccessResolver.resolve(user, AgentType.DATA_ANALYSIS);
        if (!access.toolScopes().contains(DASHBOARD_READ_SCOPE)) {
            throw new ClientException("Dashboard analysis requires administrator access");
        }
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        AgentRunCommand command = new AgentRunCommand(
                IdUtil.getSnowflakeNextIdStr(),
                IdUtil.getSnowflakeNextIdStr(),
                actualConversationId,
                user.getUserId(),
                AgentType.DATA_ANALYSIS,
                question,
                deepThinking,
                access
        );
        agentGateway.start(command, new SseAgentEventPublisher(emitter));
    }
}
