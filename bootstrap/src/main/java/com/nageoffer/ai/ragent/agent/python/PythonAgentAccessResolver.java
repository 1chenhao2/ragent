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

package com.nageoffer.ai.ragent.agent.python;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessResolver;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the explicit resource scope sent to the Python Agent runtime.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ragent.agent.runtime", havingValue = "python")
public class PythonAgentAccessResolver implements AgentAccessResolver {

    public static final String KNOWLEDGE_READ_SCOPE = "knowledge:read";
    public static final String DASHBOARD_READ_SCOPE = "dashboard:read";

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public AgentAccessContext resolve(LoginUser user, AgentType agentType) {
        if (user == null || StrUtil.isBlank(user.getUserId())) {
            return AgentAccessContext.denied();
        }
        Set<String> roles = StrUtil.isBlank(user.getRole()) ? Set.of() : Set.of(user.getRole());
        if (agentType == AgentType.DATA_ANALYSIS) {
            Set<String> toolScopes = roles.contains(UserRole.ADMIN.getCode())
                    ? Set.of(DASHBOARD_READ_SCOPE)
                    : Set.of();
            return new AgentAccessContext(null, roles, Set.of(), toolScopes);
        }
        if (agentType != AgentType.KNOWLEDGE) {
            return new AgentAccessContext(null, roles, Set.of(), Set.of());
        }

        Set<String> knowledgeBaseIds = knowledgeBaseMapper.selectList(
                        Wrappers.query(KnowledgeBaseDO.class)
                                .select("id")
                                .eq("deleted", 0)
                ).stream()
                .filter(each -> each != null && StrUtil.isNotBlank(each.getId()))
                .map(KnowledgeBaseDO::getId)
                .collect(Collectors.toUnmodifiableSet());
        return new AgentAccessContext(null, roles, knowledgeBaseIds, Set.of(KNOWLEDGE_READ_SCOPE));
    }
}
