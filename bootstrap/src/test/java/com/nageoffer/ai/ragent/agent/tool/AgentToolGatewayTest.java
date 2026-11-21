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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentToolGatewayTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Mock
    private DashboardService dashboardService;

    private AgentToolGateway toolGateway;

    @BeforeEach
    void setUp() {
        toolGateway = new AuthorizedAgentToolGateway(List.of(new KnowledgeBaseListAgentTool(knowledgeBaseMapper)));
    }

    @Test
    void shouldReturnOnlyRequestedKnowledgeBasesInsideDelegatedScope() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder()
                        .id("knowledge-1")
                        .name("Operations")
                        .collectionName("operations")
                        .embeddingModel("qwen-emb-8b")
                        .deleted(0)
                        .build(),
                KnowledgeBaseDO.builder()
                        .id("knowledge-3")
                        .name("Finance")
                        .collectionName("finance")
                        .embeddingModel("qwen-emb-8b")
                        .deleted(0)
                        .build()
        ));
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1", "knowledge-2"),
                Set.of("knowledge:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_base.list",
                "tool-call-00000001",
                "idempotency-key-0001",
                Map.of("knowledgeBaseIds", List.of("knowledge-1"))
        );

        AgentToolResult result = toolGateway.invoke(principal, command);

        assertEquals("tool-call-00000001", result.toolCallId());
        assertEquals("knowledge_base.list", result.toolName());
        assertEquals(
                List.of(Map.of(
                        "id", "knowledge-1",
                        "name", "Operations",
                        "collectionName", "operations",
                        "embeddingModel", "qwen-emb-8b"
                )),
                result.output().get("knowledgeBases")
        );
    }

    @Test
    void shouldRejectInvocationWithoutRequiredToolScope() {
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1"),
                Set.of()
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_base.list",
                "tool-call-00000002",
                "idempotency-key-0002",
                Map.of()
        );

        AgentToolException exception = assertThrows(
                AgentToolException.class,
                () -> toolGateway.invoke(principal, command)
        );

        assertEquals(403, exception.status());
        assertEquals("tool_scope_forbidden", exception.code());
    }

    @Test
    void shouldRejectUnknownTool() {
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1"),
                Set.of("knowledge:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "database.execute",
                "tool-call-00000003",
                "idempotency-key-0003",
                Map.of()
        );

        AgentToolException exception = assertThrows(
                AgentToolException.class,
                () -> toolGateway.invoke(principal, command)
        );

        assertEquals(404, exception.status());
        assertEquals("tool_not_found", exception.code());
    }

    @Test
    void shouldRejectMalformedToolCommandBeforeAuthorizationOrIdempotency() {
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1"),
                Set.of("knowledge:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_base.list",
                "",
                "short",
                Map.of()
        );

        AgentToolException exception = assertThrows(
                AgentToolException.class,
                () -> toolGateway.invoke(principal, command)
        );

        assertEquals(400, exception.status());
        assertEquals("invalid_tool_command", exception.code());
    }

    @Test
    void shouldRejectMalformedKnowledgeBaseArgumentsAsBadRequest() {
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1"),
                Set.of("knowledge:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_base.list",
                "tool-call-00000008",
                "idempotency-key-0008",
                Map.of("knowledgeBaseIds", "knowledge-1")
        );

        AgentToolException exception = assertThrows(
                AgentToolException.class,
                () -> toolGateway.invoke(principal, command)
        );

        assertEquals(400, exception.status());
        assertEquals("invalid_tool_arguments", exception.code());
    }

    @Test
    void shouldRejectKnowledgeBaseOutsideDelegatedScope() {
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1"),
                Set.of("knowledge:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_base.list",
                "tool-call-00000004",
                "idempotency-key-0004",
                Map.of("knowledgeBaseIds", List.of("knowledge-2"))
        );

        AgentToolException exception = assertThrows(
                AgentToolException.class,
                () -> toolGateway.invoke(principal, command)
        );

        assertEquals(403, exception.status());
        assertEquals("resource_scope_forbidden", exception.code());
    }

    @Test
    void shouldReplayStoredResultForSameIdempotencyKeyAndPayload() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(
                List.of(KnowledgeBaseDO.builder()
                        .id("knowledge-1")
                        .name("Operations")
                        .collectionName("operations")
                        .embeddingModel("qwen-emb-8b")
                        .deleted(0)
                        .build()),
                List.of(KnowledgeBaseDO.builder()
                        .id("knowledge-1")
                        .name("Changed after first invocation")
                        .collectionName("changed")
                        .embeddingModel("changed")
                        .deleted(0)
                        .build())
        );
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1"),
                Set.of("knowledge:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_base.list",
                "tool-call-00000005",
                "idempotency-key-0005",
                Map.of("knowledgeBaseIds", List.of("knowledge-1"))
        );

        AgentToolResult firstResult = toolGateway.invoke(principal, command);
        AgentToolResult replayedResult = toolGateway.invoke(principal, command);

        assertEquals(firstResult, replayedResult);
        assertEquals(
                List.of(Map.of(
                        "id", "knowledge-1",
                        "name", "Operations",
                        "collectionName", "operations",
                        "embeddingModel", "qwen-emb-8b"
                )),
                replayedResult.output().get("knowledgeBases")
        );
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentPayload() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder()
                        .id("knowledge-1")
                        .name("Operations")
                        .collectionName("operations")
                        .embeddingModel("qwen-emb-8b")
                        .deleted(0)
                        .build()
        ));
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1", "knowledge-2"),
                Set.of("knowledge:read")
        );
        AgentToolCommand firstCommand = new AgentToolCommand(
                "knowledge_base.list",
                "tool-call-00000006",
                "idempotency-key-0006",
                Map.of("knowledgeBaseIds", List.of("knowledge-1"))
        );
        AgentToolCommand conflictingCommand = new AgentToolCommand(
                "knowledge_base.list",
                "tool-call-00000007",
                "idempotency-key-0006",
                Map.of("knowledgeBaseIds", List.of("knowledge-2"))
        );

        toolGateway.invoke(principal, firstCommand);
        AgentToolException exception = assertThrows(
                AgentToolException.class,
                () -> toolGateway.invoke(principal, conflictingCommand)
        );

        assertEquals(409, exception.status());
        assertEquals("idempotency_conflict", exception.code());
    }

    @Test
    void shouldReturnDashboardOverviewForDelegatedAdminScope() {
        when(dashboardService.loadOverview("7d")).thenReturn(DashboardOverviewVO.builder()
                .window("7d")
                .compareWindow("prev_7d")
                .updatedAt(1710000000000L)
                .build());
        AgentToolGateway dashboardGateway = new AuthorizedAgentToolGateway(List.of(
                new DashboardOverviewAgentTool(dashboardService, new ObjectMapper())
        ));
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "admin-1",
                null,
                Set.of("admin"),
                Set.of(),
                Set.of("dashboard:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "dashboard.overview",
                "tool-call-dashboard-0001",
                "idempotency-dashboard-0001",
                Map.of("window", "7d")
        );

        AgentToolResult result = dashboardGateway.invoke(principal, command);

        assertEquals("dashboard.overview", result.toolName());
        assertEquals("7d", result.output().get("window"));
        assertEquals(1710000000000L, result.output().get("updatedAt"));
    }

    @Test
    void shouldRejectDashboardToolWithoutDelegatedScope() {
        AgentToolGateway dashboardGateway = new AuthorizedAgentToolGateway(List.of(
                new DashboardOverviewAgentTool(dashboardService, new ObjectMapper())
        ));
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of(),
                Set.of()
        );
        AgentToolCommand command = new AgentToolCommand(
                "dashboard.overview",
                "tool-call-dashboard-0002",
                "idempotency-dashboard-0002",
                Map.of()
        );

        AgentToolException exception = assertThrows(
                AgentToolException.class,
                () -> dashboardGateway.invoke(principal, command)
        );

        assertEquals(403, exception.status());
        assertEquals("tool_scope_forbidden", exception.code());
    }

    @Test
    void shouldRejectDashboardToolWindowOutsideAllowedRange() {
        AgentToolGateway dashboardGateway = new AuthorizedAgentToolGateway(List.of(
                new DashboardOverviewAgentTool(dashboardService, new ObjectMapper())
        ));
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "admin-1",
                null,
                Set.of("admin"),
                Set.of(),
                Set.of("dashboard:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "dashboard.overview",
                "tool-call-dashboard-0003",
                "idempotency-dashboard-0003",
                Map.of("window", "365d")
        );

        AgentToolException exception = assertThrows(
                AgentToolException.class,
                () -> dashboardGateway.invoke(principal, command)
        );

        assertEquals(400, exception.status());
        assertEquals("invalid_tool_arguments", exception.code());
    }
}
