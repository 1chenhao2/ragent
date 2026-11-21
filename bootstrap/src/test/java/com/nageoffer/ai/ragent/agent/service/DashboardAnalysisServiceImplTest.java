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

import com.nageoffer.ai.ragent.agent.event.AgentEventPublisher;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessResolver;
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DashboardAnalysisServiceImplTest {

    @Mock
    private AgentGateway agentGateway;

    @Mock
    private SseEmitter emitter;

    private DashboardAnalysisService dashboardAnalysisService;

    @BeforeEach
    void setUp() {
        AgentAccessResolver accessResolver = (user, agentType) -> new AgentAccessContext(
                null,
                Set.of(user.getRole()),
                Set.of(),
                "admin".equals(user.getRole()) && agentType == AgentType.DATA_ANALYSIS
                        ? Set.of("dashboard:read")
                        : Set.of()
        );
        dashboardAnalysisService = new DashboardAnalysisServiceImpl(agentGateway, accessResolver);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void shouldStartDataAnalysisAgentForAdminDashboardRequest() {
        UserContext.set(LoginUser.builder().userId("admin-1").username("admin").role("admin").build());

        dashboardAnalysisService.streamAnalysis("Summarize the dashboard.", null, false, emitter);

        ArgumentCaptor<AgentRunCommand> commandCaptor = ArgumentCaptor.forClass(AgentRunCommand.class);
        verify(agentGateway).start(commandCaptor.capture(), any(AgentEventPublisher.class));
        AgentRunCommand command = commandCaptor.getValue();
        assertEquals(AgentType.DATA_ANALYSIS, command.agentType());
        assertEquals("admin-1", command.userId());
        assertEquals(Set.of("dashboard:read"), command.access().toolScopes());
        assertEquals("Summarize the dashboard.", command.input());
        assertTrue(command.conversationId() != null && !command.conversationId().isBlank());
    }

    @Test
    void shouldRejectDashboardAnalysisWithoutDelegatedDashboardScope() {
        UserContext.set(LoginUser.builder().userId("user-1").username("tester").role("user").build());

        assertThrows(
                ClientException.class,
                () -> dashboardAnalysisService.streamAnalysis("Summarize the dashboard.", null, false, emitter)
        );

        verify(agentGateway, never()).start(any(), any());
    }
}
