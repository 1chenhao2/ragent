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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.event.AgentEvent;
import com.nageoffer.ai.ragent.agent.event.AgentEventPublisher;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PythonAgentGatewayFailureContractTest {

    private static final String SECRET = "test-delegated-token-secret-0123456789";

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldMapPythonProblemDetailsToAStableGatewayError() {
        server.enqueue(new MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("""
                        {
                          "type": "about:blank",
                          "title": "Forbidden",
                          "status": 403,
                          "detail": "Knowledge base access was denied",
                          "code": "access_knowledge_base_mismatch"
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway(Duration.ofSeconds(2)).start(command(), new NoOpPublisher())
        );

        assertEquals(403, error.getStatus());
        assertEquals("access_knowledge_base_mismatch", error.getCode());
        assertEquals("Knowledge base access was denied", error.getMessage());
    }

    @Test
    void shouldFailCreateWhenThePythonServiceTimesOut() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway(Duration.ofMillis(100)).start(command(), new NoOpPublisher())
        );

        assertEquals(0, error.getStatus());
        assertEquals("agent_service_unavailable", error.getCode());
    }

    private AgentGateway gateway(Duration requestTimeout) {
        PythonAgentProperties properties = new PythonAgentProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setRequestTimeout(requestTimeout);
        properties.getDelegatedToken().setSecret(SECRET);
        return new PythonAgentGateway(
                new OkHttpClient(),
                new ObjectMapper(),
                properties,
                new DelegatedAgentTokenIssuer(properties)
        );
    }

    private AgentRunCommand command() {
        return new AgentRunCommand(
                "request-id-00000001",
                "java-run-1",
                "conversation-1",
                "user-1",
                AgentType.KNOWLEDGE,
                "question",
                false,
                new AgentAccessContext(null, Set.of("user"), Set.of(), Set.of("knowledge:read"))
        );
    }

    private static final class NoOpPublisher implements AgentEventPublisher {

        @Override
        public void publish(AgentEvent event) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void fail(Throwable throwable) {
        }
    }
}
