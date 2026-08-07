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

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.event.AgentEvent;
import com.nageoffer.ai.ragent.agent.event.AgentEventPublisher;
import com.nageoffer.ai.ragent.agent.event.AgentEventType;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunHandle;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonAgentGatewayContractTest {

    private static final String SECRET = "test-delegated-token-secret-0123456789";
    private static final String REMOTE_RUN_ID = "python-run-1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockWebServer server;
    private AgentGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        PythonAgentProperties properties = new PythonAgentProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.getDelegatedToken().setSecret(SECRET);
        gateway = new PythonAgentGateway(
                new OkHttpClient(),
                objectMapper,
                properties,
                new DelegatedAgentTokenIssuer(properties)
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void shouldCreateRunAndPublishPythonEventsThroughAgentGateway() throws Exception {
        server.enqueue(jsonResponse(202, """
                {
                  "runId": "python-run-1",
                  "status": "accepted",
                  "eventStreamUrl": "/internal/v1/agent-runs/python-run-1/events"
                }
                """));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        id: 0
                        event: run_started
                        data: {"runId":"python-run-1","sequence":0,"type":"run_started","timestamp":"2026-08-07T00:00:00Z","data":{"conversationId":"conversation-1","agentType":"knowledge"}}

                        id: 1
                        event: thinking_delta
                        data: {"runId":"python-run-1","sequence":1,"type":"thinking_delta","timestamp":"2026-08-07T00:00:01Z","data":{"delta":"thinking"}}

                        id: 2
                        event: answer_delta
                        data: {"runId":"python-run-1","sequence":2,"type":"answer_delta","timestamp":"2026-08-07T00:00:02Z","data":{"delta":"answer"}}

                        id: 3
                        event: run_completed
                        data: {"runId":"python-run-1","sequence":3,"type":"run_completed","timestamp":"2026-08-07T00:00:03Z","data":{"messageId":"message-1","title":"Conversation"}}

                        """));
        RecordingPublisher publisher = new RecordingPublisher();

        AgentRunHandle handle = gateway.start(command(), publisher);

        assertEquals(REMOTE_RUN_ID, handle.runId());
        assertEquals("conversation-1", handle.conversationId());
        assertTrue(publisher.awaitTerminal());
        assertNull(publisher.failure.get());
        assertEquals(
                List.of(
                        AgentEventType.RUN_STARTED,
                        AgentEventType.THINKING_DELTA,
                        AgentEventType.ANSWER_DELTA,
                        AgentEventType.COMPLETED
                ),
                publisher.events.stream().map(AgentEvent::type).toList()
        );
        assertEquals("thinking", ((MessageDelta) publisher.events.get(1).data()).delta());
        assertEquals("answer", ((MessageDelta) publisher.events.get(2).data()).delta());
        assertEquals("message-1", ((CompletionPayload) publisher.events.get(3).data()).messageId());

        RecordedRequest createRequest = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/internal/v1/agent-runs", createRequest.getPath());
        assertEquals("request-id-00000001", createRequest.getHeader("Idempotency-Key"));
        JsonNode requestBody = objectMapper.readTree(createRequest.getBody().readUtf8());
        assertEquals("user-1", requestBody.at("/access/userId").asText());
        assertEquals("admin", requestBody.at("/access/roles/0").asText());
        assertEquals("knowledge-1", requestBody.at("/access/allowedKnowledgeBaseIds/0").asText());
        assertEquals("knowledge:read", requestBody.at("/access/toolScopes/0").asText());

        DecodedJWT token = verifyToken(createRequest.getHeader("Authorization"));
        assertEquals("user-1", token.getSubject());
        assertTrue(token.getIssuedAtAsInstant() != null);
        assertTrue(token.getExpiresAtAsInstant().isAfter(token.getIssuedAtAsInstant()));
        assertEquals("tenant-1", token.getClaim("tenantId").asString());
        assertEquals(List.of("admin"), token.getClaim("roles").asList(String.class));
        assertEquals(
                List.of("knowledge-1"),
                token.getClaim("allowedKnowledgeBaseIds").asList(String.class)
        );
        assertEquals(List.of("knowledge:read"), token.getClaim("toolScopes").asList(String.class));

        RecordedRequest streamRequest = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/internal/v1/agent-runs/python-run-1/events", streamRequest.getPath());
        assertEquals(createRequest.getHeader("Authorization"), streamRequest.getHeader("Authorization"));
    }

    @Test
    void shouldCancelTheAcceptedPythonRun() throws Exception {
        server.enqueue(jsonResponse(202, """
                {
                  "runId": "python-run-1",
                  "status": "accepted",
                  "eventStreamUrl": "/internal/v1/agent-runs/python-run-1/events"
                }
                """));
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBodyDelay(2, TimeUnit.SECONDS)
                .setSocketPolicy(SocketPolicy.KEEP_OPEN)
                .setBody("""
                        id: 0
                        event: run_started
                        data: {"runId":"python-run-1","sequence":0,"type":"run_started","timestamp":"2026-08-07T00:00:00Z","data":{"conversationId":"conversation-1"}}

                        """));

        AgentRunHandle handle = gateway.start(command(), new RecordingPublisher());
        RecordedRequest createRequest = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest streamRequest = server.takeRequest(1, TimeUnit.SECONDS);
        server.enqueue(jsonResponse(202, """
                {
                  "runId": "python-run-1",
                  "status": "cancelled",
                  "updatedAt": "2026-08-07T00:00:01Z"
                }
                """));

        gateway.cancel(handle.runId());

        assertEquals("/internal/v1/agent-runs", createRequest.getPath());
        assertEquals("/internal/v1/agent-runs/python-run-1/events", streamRequest.getPath());
        RecordedRequest cancelRequest = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("POST", cancelRequest.getMethod());
        assertEquals("/internal/v1/agent-runs/python-run-1/cancel", cancelRequest.getPath());
        assertTrue(cancelRequest.getHeader("Idempotency-Key").length() >= 16);
        assertEquals(createRequest.getHeader("Authorization"), cancelRequest.getHeader("Authorization"));
    }

    private AgentRunCommand command() {
        return new AgentRunCommand(
                "request-id-00000001",
                "java-run-1",
                "conversation-1",
                "user-1",
                AgentType.KNOWLEDGE,
                "question",
                true,
                new AgentAccessContext(
                        "tenant-1",
                        Set.of("admin"),
                        Set.of("knowledge-1"),
                        Set.of("knowledge:read")
                )
        );
    }

    private DecodedJWT verifyToken(String authorization) {
        String token = authorization.substring("Bearer ".length());
        return JWT.require(Algorithm.HMAC256(SECRET))
                .withIssuer("ragent-java")
                .withAudience("ragent-agent-service")
                .build()
                .verify(token);
    }

    private MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private static final class RecordingPublisher implements AgentEventPublisher {

        private final List<AgentEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void publish(AgentEvent event) {
            events.add(event);
        }

        @Override
        public void complete() {
            terminal.countDown();
        }

        @Override
        public void fail(Throwable throwable) {
            failure.set(throwable);
            terminal.countDown();
        }

        private boolean awaitTerminal() throws InterruptedException {
            return terminal.await(2, TimeUnit.SECONDS);
        }
    }
}
