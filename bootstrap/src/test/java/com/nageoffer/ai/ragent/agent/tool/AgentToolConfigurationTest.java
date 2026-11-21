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
import com.nageoffer.ai.ragent.agent.python.PythonAgentProperties;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentToolConfigurationTest {

    @Test
    void shouldNotRegisterBusinessToolModuleForLegacyRuntime() {
        contextRunner().run(context -> assertThat(context).doesNotHaveBean(AgentToolGateway.class));
    }

    @Test
    void shouldRegisterRedisBackedBusinessToolModuleForPythonRuntime() {
        contextRunner()
                .withPropertyValues("ragent.agent.runtime=python")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentToolGateway.class);
                    assertThat(context).hasSingleBean(AgentToolTokenVerifier.class);
                    assertThat(context).hasSingleBean(RedisAgentToolIdempotencyStore.class);
                });
    }

    private ApplicationContextRunner contextRunner() {
        PythonAgentProperties properties = new PythonAgentProperties();
        properties.getDelegatedToken().setSecret("test-delegated-token-secret-0123456789");
        return new ApplicationContextRunner()
                .withBean(PythonAgentProperties.class, () -> properties)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(KnowledgeBaseMapper.class, () -> mock(KnowledgeBaseMapper.class))
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withUserConfiguration(AgentToolConfiguration.class);
    }
}
