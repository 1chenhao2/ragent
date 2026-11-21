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

package com.nageoffer.ai.ragent.agent.legacy;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessResolver;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Keeps the legacy runtime access metadata independent of Python resource resolution.
 */
@Component
@ConditionalOnProperty(name = "ragent.agent.runtime", havingValue = "legacy", matchIfMissing = true)
public class LegacyAgentAccessResolver implements AgentAccessResolver {

    @Override
    public AgentAccessContext resolve(LoginUser user, AgentType agentType) {
        if (user == null || StrUtil.isBlank(user.getUserId())) {
            return AgentAccessContext.denied();
        }
        Set<String> roles = StrUtil.isBlank(user.getRole()) ? Set.of() : Set.of(user.getRole());
        Set<String> toolScopes = agentType == AgentType.KNOWLEDGE ? Set.of("knowledge:read") : Set.of();
        return new AgentAccessContext(null, roles, Set.of(), toolScopes);
    }
}
