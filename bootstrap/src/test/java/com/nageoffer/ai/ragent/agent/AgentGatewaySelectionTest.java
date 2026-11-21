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

package com.nageoffer.ai.ragent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.conversation.AgentConversationRecorder;
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.legacy.LegacyRagAgentGateway;
import com.nageoffer.ai.ragent.agent.python.DelegatedAgentTokenIssuer;
import com.nageoffer.ai.ragent.agent.python.PythonAgentGateway;
import com.nageoffer.ai.ragent.agent.python.PythonAgentProperties;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatPipeline;
import com.nageoffer.ai.ragent.rag.service.ratelimit.ChatQueueLimiter;
import com.nageoffer.ai.ragent.rag.trace.StreamChatTraceRunner;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

class AgentGatewaySelectionTest {

    @Test
    void shouldUseLegacyAgentGatewayByDefault() {
        contextRunner().run(context -> {
            AgentGateway gateway = context.getBean(AgentGateway.class);
            assertInstanceOf(LegacyRagAgentGateway.class, gateway);
        });
    }

    @Test
    void shouldUseOnlyPythonAgentGatewayWhenExplicitlyEnabled() {
        contextRunner()
                .withPropertyValues("ragent.agent.runtime=python")
                .run(context -> {
                    AgentGateway gateway = context.getBean(AgentGateway.class);
                    assertInstanceOf(PythonAgentGateway.class, gateway);
                });
    }

    private ApplicationContextRunner contextRunner() {
        PythonAgentProperties properties = new PythonAgentProperties();
        properties.getDelegatedToken().setSecret("test-delegated-token-secret-0123456789");
        return new ApplicationContextRunner()
                .withBean(StreamChatPipeline.class, () -> mock(StreamChatPipeline.class))
                .withBean(ChatQueueLimiter.class, () -> mock(ChatQueueLimiter.class))
                .withBean(StreamCallbackFactory.class, () -> mock(StreamCallbackFactory.class))
                .withBean(StreamChatTraceRunner.class, () -> mock(StreamChatTraceRunner.class))
                .withBean(StreamTaskManager.class, () -> mock(StreamTaskManager.class))
                .withBean("streamingHttpClient", OkHttpClient.class, OkHttpClient::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(PythonAgentProperties.class, () -> properties)
                .withBean(DelegatedAgentTokenIssuer.class, () -> new DelegatedAgentTokenIssuer(properties))
                .withBean(AgentConversationRecorder.class, () -> mock(AgentConversationRecorder.class))
                .withUserConfiguration(LegacyRagAgentGateway.class, PythonAgentGateway.class);
    }
}
