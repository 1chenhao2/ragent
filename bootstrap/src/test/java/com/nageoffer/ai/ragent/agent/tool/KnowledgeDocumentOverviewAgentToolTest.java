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

import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentOverviewRow;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentOverviewAgentToolTest {

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    private AgentToolGateway toolGateway;

    @BeforeEach
    void setUp() {
        toolGateway = new AuthorizedAgentToolGateway(
                List.of(new KnowledgeDocumentOverviewAgentTool(knowledgeBaseMapper, knowledgeDocumentMapper)));
    }

    @Test
    void shouldExposeKnowledgeDocumentOverviewToolName() {
        AgentTool tool = new KnowledgeDocumentOverviewAgentTool(knowledgeBaseMapper, knowledgeDocumentMapper);

        assertEquals("knowledge_document.overview", tool.name());
    }

    @Test
    void shouldRequireKnowledgeReadScope() {
        AgentTool tool = new KnowledgeDocumentOverviewAgentTool(knowledgeBaseMapper, knowledgeDocumentMapper);

        assertEquals(Set.of("knowledge:read"), tool.requiredScopes());
    }

    @Test
    void shouldReturnBoundedAggregateForRequestedKnowledgeBasesWithinDelegatedScope() {
        when(knowledgeDocumentMapper.aggregateOverview(any()))
                .thenReturn(List.of(
                        overview("knowledge-1", "Operations", "pending", 1, 0L, 0L),
                        overview("knowledge-1", "Operations", "running", 1, 12L, 65_000L),
                        overview("knowledge-1", "Operations", "failed", 1, 0L, 0L),
                        overview("knowledge-1", "Operations", "success", 8, 487L, 11_678_678L),
                        overview("knowledge-1", "Operations", null, 2, 0L, 1_234_567L)
                ));
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1"),
                Set.of("knowledge:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_document.overview",
                "tool-call-kdov-0001",
                "idempotency-kdov-0001",
                Map.of("knowledgeBaseIds", List.of("knowledge-1"))
        );

        AgentToolResult result = toolGateway.invoke(principal, command);

        assertEquals("knowledge_document.overview", result.toolName());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> knowledgeBases =
                (List<Map<String, Object>>) result.output().get("knowledgeBases");
        assertEquals(1, knowledgeBases.size());
        Map<String, Object> kb = knowledgeBases.get(0);
        assertEquals("knowledge-1", kb.get("id"));
        assertEquals("Operations", kb.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) kb.get("totals");
        assertEquals(13, totals.get("documents"));
        assertEquals(499L, totals.get("chunks"));
        assertEquals(12_978_245L, totals.get("bytes"));
        @SuppressWarnings("unchecked")
        Map<String, Object> byStatus = (Map<String, Object>) kb.get("byStatus");
        assertEquals(1, byStatus.get("pending"));
        assertEquals(1, byStatus.get("running"));
        assertEquals(1, byStatus.get("failed"));
        assertEquals(8, byStatus.get("success"));
        assertEquals(2, byStatus.get("disabled"));
    }

    @Test
    void shouldFallbackToDelegatedScopeWhenKnowledgeBaseIdsIsAbsent() {
        when(knowledgeDocumentMapper.aggregateOverview(any()))
                .thenReturn(List.of(
                        overview("knowledge-1", "Operations", "success", 5, 100L, 0L),
                        overview("knowledge-2", "Finance", "success", 3, 60L, 0L)
                ));
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1", "knowledge-2"),
                Set.of("knowledge:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_document.overview",
                "tool-call-kdov-0002",
                "idempotency-kdov-0002",
                Map.of()
        );

        AgentToolResult result = toolGateway.invoke(principal, command);

        ArgumentCaptor<Collection<String>> queried = ArgumentCaptor.forClass(Collection.class);
        verify(knowledgeDocumentMapper).aggregateOverview(queried.capture());
        assertEquals(Set.of("knowledge-1", "knowledge-2"), Set.copyOf(queried.getValue()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> knowledgeBases =
                (List<Map<String, Object>>) result.output().get("knowledgeBases");
        assertEquals(2, knowledgeBases.size());
        assertEquals(List.of("knowledge-1", "knowledge-2"),
                knowledgeBases.stream().map(item -> (String) item.get("id")).toList());
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
                "knowledge_document.overview",
                "tool-call-kdov-0003",
                "idempotency-kdov-0003",
                Map.of("knowledgeBaseIds", List.of("knowledge-2"))
        );

        AgentToolException exception = assertThrows(
                AgentToolException.class,
                () -> toolGateway.invoke(principal, command)
        );

        assertEquals(403, exception.status());
        assertEquals("resource_scope_forbidden", exception.code());
        verify(knowledgeDocumentMapper, never()).aggregateOverview(any());
    }

    @Test
    void shouldRejectNonArrayKnowledgeBaseIdsArgument() {
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1"),
                Set.of("knowledge:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_document.overview",
                "tool-call-kdov-0004",
                "idempotency-kdov-0004",
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
    void shouldRejectInvocationWithoutRequiredToolScope() {
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1"),
                Set.of()
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_document.overview",
                "tool-call-kdov-0005",
                "idempotency-kdov-0005",
                Map.of("knowledgeBaseIds", List.of("knowledge-1"))
        );

        AgentToolException exception = assertThrows(
                AgentToolException.class,
                () -> toolGateway.invoke(principal, command)
        );

        assertEquals(403, exception.status());
        assertEquals("tool_scope_forbidden", exception.code());
    }

    @Test
    void shouldNotLeakKnowledgeBasesNotInDelegatedScope() {
        when(knowledgeDocumentMapper.aggregateOverview(any()))
                .thenReturn(List.of(
                        overview("knowledge-1", "Operations", "success", 5, 100L, 0L),
                        overview("knowledge-9", "Unknown", "success", 5, 100L, 0L)
                ));
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of("knowledge-1"),
                Set.of("knowledge:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_document.overview",
                "tool-call-kdov-0006",
                "idempotency-kdov-0006",
                Map.of("knowledgeBaseIds", List.of("knowledge-1"))
        );

        AgentToolResult result = toolGateway.invoke(principal, command);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> knowledgeBases =
                (List<Map<String, Object>>) result.output().get("knowledgeBases");
        assertEquals(1, knowledgeBases.size());
        assertEquals("knowledge-1", knowledgeBases.get(0).get("id"));
    }

    @Test
    void shouldReturnEmptyKnowledgeBasesWhenDelegatedScopeIsEmpty() {
        AgentToolPrincipal principal = new AgentToolPrincipal(
                "user-1",
                null,
                Set.of("user"),
                Set.of(),
                Set.of("knowledge:read")
        );
        AgentToolCommand command = new AgentToolCommand(
                "knowledge_document.overview",
                "tool-call-kdov-0007",
                "idempotency-kdov-0007",
                Map.of()
        );

        AgentToolResult result = toolGateway.invoke(principal, command);

        assertEquals(List.of(), result.output().get("knowledgeBases"));
        verify(knowledgeDocumentMapper, never()).aggregateOverview(any());
    }

    private static KnowledgeDocumentOverviewRow overview(
            String kbId,
            String kbName,
            String status,
            long documentCount,
            long chunkTotal,
            long byteTotal
    ) {
        Integer enabled = status == null ? 0 : 1;
        return new KnowledgeDocumentOverviewRow(kbId, kbName, status, enabled, documentCount, chunkTotal, byteTotal);
    }
}