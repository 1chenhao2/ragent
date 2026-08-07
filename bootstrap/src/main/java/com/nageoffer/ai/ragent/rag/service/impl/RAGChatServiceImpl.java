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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.adapter.sse.SseAgentEventPublisher;
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG chat compatibility facade.
 */
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final AgentGateway agentGateway;

    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String runId = IdUtil.getSnowflakeNextIdStr();
        AgentRunCommand command = new AgentRunCommand(
                IdUtil.getSnowflakeNextIdStr(),
                runId,
                actualConversationId,
                UserContext.getUserId(),
                AgentType.KNOWLEDGE,
                question,
                Boolean.TRUE.equals(deepThinking)
        );
        agentGateway.start(command, new SseAgentEventPublisher(emitter));
    }

    @Override
    public void stopTask(String taskId) {
        agentGateway.cancel(taskId);
    }
}
