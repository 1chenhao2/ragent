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

import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessResolver;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PythonAgentAccessResolverTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    private AgentAccessResolver accessResolver;

    @BeforeEach
    void setUp() {
        accessResolver = new PythonAgentAccessResolver(knowledgeBaseMapper);
    }

    @Test
    void shouldResolveActiveKnowledgeBasesForKnowledgeAgent() {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder().id("knowledge-2").deleted(0).build(),
                KnowledgeBaseDO.builder().id("knowledge-1").deleted(0).build()
        ));
        LoginUser user = LoginUser.builder()
                .userId("user-1")
                .username("tester")
                .role("user")
                .build();

        AgentAccessContext access = accessResolver.resolve(user, AgentType.KNOWLEDGE);

        assertNull(access.tenantId());
        assertEquals(Set.of("user"), access.roles());
        assertEquals(Set.of("knowledge-1", "knowledge-2"), access.allowedKnowledgeBaseIds());
        assertEquals(Set.of("knowledge:read"), access.toolScopes());
    }

    @Test
    void shouldGrantDashboardScopeOnlyToAdminDataAnalysisAgent() {
        LoginUser admin = LoginUser.builder()
                .userId("admin-1")
                .username("admin")
                .role("admin")
                .build();
        LoginUser standardUser = LoginUser.builder()
                .userId("user-1")
                .username("tester")
                .role("user")
                .build();

        AgentAccessContext adminAccess = accessResolver.resolve(admin, AgentType.DATA_ANALYSIS);
        AgentAccessContext userAccess = accessResolver.resolve(standardUser, AgentType.DATA_ANALYSIS);

        assertEquals(Set.of("admin"), adminAccess.roles());
        assertEquals(Set.of("dashboard:read"), adminAccess.toolScopes());
        assertEquals(Set.of(), adminAccess.allowedKnowledgeBaseIds());
        assertEquals(Set.of(), userAccess.toolScopes());
    }
}
