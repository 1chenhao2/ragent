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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class KnowledgeBaseListAgentTool implements AgentTool {

    private static final String TOOL_NAME = "knowledge_base.list";

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public KnowledgeBaseListAgentTool(KnowledgeBaseMapper knowledgeBaseMapper) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
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

        List<Map<String, Object>> knowledgeBases = knowledgeBaseMapper.selectList(
                        Wrappers.<KnowledgeBaseDO>lambdaQuery().in(KnowledgeBaseDO::getId, selectedIds)
                ).stream()
                .filter(knowledgeBase -> selectedIds.contains(knowledgeBase.getId()))
                .map(this::toOutput)
                .toList();
        return Map.of("knowledgeBases", knowledgeBases);
    }

    private Set<String> requestedIds(Object value) {
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof Collection<?> values)) {
            throw new AgentToolException(400, "invalid_tool_arguments", "knowledgeBaseIds must be an array");
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

    private Map<String, Object> toOutput(KnowledgeBaseDO knowledgeBase) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("id", knowledgeBase.getId());
        output.put("name", knowledgeBase.getName());
        output.put("collectionName", knowledgeBase.getCollectionName());
        output.put("embeddingModel", knowledgeBase.getEmbeddingModel());
        return output;
    }
}
