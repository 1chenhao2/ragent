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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.event.AgentEventPublisher;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessResolver;
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RAGChatServiceImplTest {

    @Mock
    private AgentGateway agentGateway;

    @Mock
    private SseEmitter emitter;

    private RAGChatServiceImpl ragChatService;

    @BeforeEach
    void setUp() {
        UserContext.set(LoginUser.builder().userId("user-1").username("tester").role("user").build());
        AgentAccessResolver accessResolver = (user, agentType) -> new AgentAccessContext(
                null,
                Set.of("user"),
                Set.of("knowledge-1"),
                Set.of("knowledge:read")
        );
        ragChatService = new RAGChatServiceImpl(agentGateway, accessResolver);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldCreateKnowledgeAgentRunForExistingChatRequest() {
        ragChatService.streamChat("question", null, true, emitter);

        ArgumentCaptor<AgentRunCommand> commandCaptor = ArgumentCaptor.forClass(AgentRunCommand.class);
        verify(agentGateway).start(commandCaptor.capture(), any(AgentEventPublisher.class));
        AgentRunCommand command = commandCaptor.getValue();
        assertTrue(StrUtil.isNotBlank(command.requestId()));
        assertTrue(StrUtil.isNotBlank(command.runId()));
        assertTrue(StrUtil.isNotBlank(command.conversationId()));
        assertEquals("user-1", command.userId());
        assertEquals(Set.of("user"), command.access().roles());
        assertEquals(Set.of("knowledge-1"), command.access().allowedKnowledgeBaseIds());
        assertEquals(Set.of("knowledge:read"), command.access().toolScopes());
        assertNull(command.access().tenantId());
        assertEquals(AgentType.KNOWLEDGE, command.agentType());
        assertEquals("question", command.input());
        assertTrue(command.deepThinking());
    }

    @Test
    void shouldCancelExistingTaskThroughAgentGateway() {
        ragChatService.stopTask("run-1");

        verify(agentGateway).cancel("run-1");
    }
}
