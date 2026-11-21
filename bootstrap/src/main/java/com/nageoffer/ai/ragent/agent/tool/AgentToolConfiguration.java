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
import com.nageoffer.ai.ragent.admin.service.DashboardService;
import com.nageoffer.ai.ragent.agent.python.PythonAgentProperties;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@Configuration
@ConditionalOnProperty(name = "ragent.agent.runtime", havingValue = "python")
@EnableConfigurationProperties(AgentToolProperties.class)
public class AgentToolConfiguration {

    @Bean
    public AgentToolTokenVerifier agentToolTokenVerifier(PythonAgentProperties properties) {
        return new AgentToolTokenVerifier(properties);
    }

    @Bean
    public AgentTool knowledgeBaseListAgentTool(KnowledgeBaseMapper knowledgeBaseMapper) {
        return new KnowledgeBaseListAgentTool(knowledgeBaseMapper);
    }

    @Bean
    public AgentTool dashboardOverviewAgentTool(DashboardService dashboardService, ObjectMapper objectMapper) {
        return new DashboardOverviewAgentTool(dashboardService, objectMapper);
    }

    @Bean
    public AgentTool knowledgeDocumentOverviewAgentTool(
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeDocumentMapper knowledgeDocumentMapper
    ) {
        return new KnowledgeDocumentOverviewAgentTool(knowledgeBaseMapper, knowledgeDocumentMapper);
    }

    @Bean
    public RedisAgentToolIdempotencyStore agentToolIdempotencyStore(
            RedissonClient redissonClient,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AgentToolProperties properties
    ) {
        return new RedisAgentToolIdempotencyStore(redissonClient, redisTemplate, objectMapper, properties);
    }

    @Bean
    public AgentToolGateway agentToolGateway(
            List<AgentTool> tools,
            RedisAgentToolIdempotencyStore idempotencyStore
    ) {
        return new AuthorizedAgentToolGateway(tools, idempotencyStore);
    }
}
