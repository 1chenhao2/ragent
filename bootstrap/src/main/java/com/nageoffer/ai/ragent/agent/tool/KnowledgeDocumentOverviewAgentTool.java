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

import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentOverviewRow;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only, bounded overview of knowledge documents for the delegated knowledge base scope.
 *
 * <p>Returns aggregate counts grouped by knowledge base and processing status. Document IDs,
 * names, and full bodies never leave Java. Used by the {@code business} Agent to summarise
 * ingestion progress without exposing raw document rows.
 */
public class KnowledgeDocumentOverviewAgentTool implements AgentTool {

    private static final String TOOL_NAME = "knowledge_document.overview";
    private static final Set<String> KNOWN_STATUSES = Set.of("pending", "running", "failed", "success");

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    public KnowledgeDocumentOverviewAgentTool(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeDocumentMapper knowledgeDocumentMapper
    ) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Set<String> requiredScopes() {
        return Set.of("knowledge:read");
    }

    @Override
    public Map<String, Object> invoke(AgentToolPrincipal principal, Map<String, Object> arguments) {
        Set<String> requestedIds = requestedIds(arguments.get("knowledgeBaseIds"));
        Set<String> delegatedIds = principal.allowedKnowledgeBaseIds();
        if (!delegatedIds.containsAll(requestedIds)) {
            throw new AgentToolException(
                    403,
                    "resource_scope_forbidden",
                    "Requested knowledge base is outside delegated scope"
            );
        }

        Set<String> selectedIds = requestedIds.isEmpty() ? delegatedIds : requestedIds;
        if (selectedIds.isEmpty()) {
            return Map.of("knowledgeBases", List.of());
        }

        Map<String, String> knowledgeBaseNames = lookupKnowledgeBaseNames(selectedIds);
        List<KnowledgeDocumentOverviewRow> rows = knowledgeDocumentMapper.aggregateOverview(selectedIds).stream()
                .filter(row -> selectedIds.contains(row.kbId()))
                .toList();

        Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
        for (String id : selectedIds) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("id", id);
            output.put("name", knowledgeBaseNames.getOrDefault(id, ""));
            output.put("totals", new LinkedHashMap<>(Map.of(
                    "documents", 0L,
                    "chunks", 0L,
                    "bytes", 0L
            )));
            output.put("byStatus", new LinkedHashMap<>(Map.of(
                    "pending", 0L,
                    "running", 0L,
                    "failed", 0L,
                    "success", 0L,
                    "disabled", 0L
            )));
            grouped.put(id, output);
        }

        Map<String, long[]> totals = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byStatus = new LinkedHashMap<>();
        for (KnowledgeDocumentOverviewRow row : rows) {
            String id = row.kbId();
            totals.computeIfAbsent(id, key -> new long[3]);
            long[] rowTotals = totals.get(id);
            rowTotals[0] += row.documentCount();
            rowTotals[1] += row.chunkTotal();
            rowTotals[2] += row.byteTotal();

            String bucket = bucket(row.status(), row.enabled());
            Map<String, Long> statusMap = byStatus.computeIfAbsent(id, key -> new LinkedHashMap<>());
            statusMap.merge(bucket, row.documentCount(), Long::sum);
        }

        List<Map<String, Object>> output = new ArrayList<>(selectedIds.size());
        for (String id : selectedIds) {
            Map<String, Object> entry = grouped.get(id);
            long[] rowTotals = totals.getOrDefault(id, new long[3]);
            entry.put("totals", new LinkedHashMap<>(Map.of(
                    "documents", rowTotals[0],
                    "chunks", rowTotals[1],
                    "bytes", rowTotals[2]
            )));
            Map<String, Long> statusMap = byStatus.getOrDefault(id, Map.of());
            Map<String, Object> statusOutput = new LinkedHashMap<>(Map.of(
                    "pending", 0L,
                    "running", 0L,
                    "failed", 0L,
                    "success", 0L,
                    "disabled", 0L
            ));
            statusMap.forEach(statusOutput::put);
            entry.put("byStatus", statusOutput);
            output.add(entry);
        }
        return Map.of("knowledgeBases", output);
    }

    private Map<String, String> lookupKnowledgeBaseNames(Collection<String> kbIds) {
        return knowledgeBaseMapper.selectBatchIds(kbIds).stream()
                .collect(Collectors.toUnmodifiableMap(
                        KnowledgeBaseDO::getId,
                        kb -> kb.getName() == null ? "" : kb.getName(),
                        (a, b) -> a
                ));
    }

    private static String bucket(String status, Integer enabled) {
        if (enabled != null && enabled == 0) {
            return "disabled";
        }
        if (status == null) {
            return "disabled";
        }
        String normalized = status.toLowerCase();
        if (KNOWN_STATUSES.contains(normalized)) {
            return normalized;
        }
        return "disabled";
    }

    private static Set<String> requestedIds(Object value) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof Collection<?> values)) {
            throw new AgentToolException(
                    400,
                    "invalid_tool_arguments",
                    "knowledgeBaseIds must be an array"
            );
        }
        return values.stream()
                .map(item -> {
                    if (!(item instanceof String id) || id.isBlank()) {
                        throw new AgentToolException(
                                400,
                                "invalid_tool_arguments",
                                "knowledgeBaseIds must contain non-blank strings"
                        );
                    }
                    return id;
                })
                .collect(Collectors.toUnmodifiableSet());
    }
}