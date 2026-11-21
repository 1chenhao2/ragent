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
import com.nageoffer.ai.ragent.agent.conversation.AgentConversationRecorder;
import com.nageoffer.ai.ragent.agent.conversation.AgentConversationSession;
import com.nageoffer.ai.ragent.agent.event.AgentEvent;
import com.nageoffer.ai.ragent.agent.event.AgentEventPublisher;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void shouldRejectDelegatedTokenTtlThatDoesNotCoverTheRunDeadline() {
        PythonAgentProperties properties = new PythonAgentProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setRunTimeout(Duration.ofMinutes(5));
        properties.getDelegatedToken().setSecret(SECRET);
        properties.getDelegatedToken().setTtl(Duration.ofMinutes(5));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new PythonAgentGateway(
                        new OkHttpClient(),
                        new ObjectMapper(),
                        properties,
                        new DelegatedAgentTokenIssuer(properties)
                )
        );

        assertEquals(
                "ragent.agent.python.delegated-token.ttl must exceed run-timeout",
                error.getMessage()
        );
    }

    @Test
    void shouldCancelAndFailARunThatExceedsItsDeadline() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""
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
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"runId\":\"python-run-1\",\"status\":\"cancelled\"}"));
        RecordingFailurePublisher publisher = new RecordingFailurePublisher();

        gateway(Duration.ofSeconds(2), Duration.ofMillis(100)).start(command(), publisher);

        assertTrue(publisher.awaitFailure());
        AgentGatewayException error = (AgentGatewayException) publisher.failure.get();
        assertEquals(504, error.getStatus());
        assertEquals("agent_run_timeout", error.getCode());
        server.takeRequest(1, TimeUnit.SECONDS);
        server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest cancelRequest = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("/internal/v1/agent-runs/python-run-1/cancel", cancelRequest.getPath());
    }

    @Test
    void shouldPersistPartialAnswerWhenPythonRunFails() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(202)
                .setHeader("Content-Type", "application/json")
                .setBody("""
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
                        event: answer_delta
                        data: {"runId":"python-run-1","sequence":0,"type":"answer_delta","timestamp":"2026-08-07T00:00:00Z","data":{"delta":"partial answer"}}

                        id: 1
                        event: run_failed
                        data: {"runId":"python-run-1","sequence":1,"type":"run_failed","timestamp":"2026-08-07T00:00:01Z","data":{"error":{"code":"llm_provider_failed","detail":"Provider failed"}}}

                        """));
        RecordingConversationRecorder recorder = new RecordingConversationRecorder();
        RecordingTerminalPublisher publisher = new RecordingTerminalPublisher();

        gateway(Duration.ofSeconds(2), Duration.ofMinutes(5), recorder).start(command(), publisher);

        assertTrue(publisher.awaitTerminal());
        assertEquals("partial answer", recorder.answer.get());
        assertEquals(ChatMessage.MessageStatus.INTERRUPTED, recorder.status.get());
    }

    private AgentGateway gateway(Duration requestTimeout) {
        return gateway(requestTimeout, Duration.ofMinutes(5));
    }

    private AgentGateway gateway(Duration requestTimeout, Duration runTimeout) {
        return gateway(requestTimeout, runTimeout, null);
    }

    private AgentGateway gateway(
            Duration requestTimeout,
            Duration runTimeout,
            AgentConversationRecorder conversationRecorder
    ) {
        PythonAgentProperties properties = new PythonAgentProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setRequestTimeout(requestTimeout);
        properties.setRunTimeout(runTimeout);
        properties.getDelegatedToken().setSecret(SECRET);
        if (conversationRecorder == null) {
            return new PythonAgentGateway(
                    new OkHttpClient(),
                    new ObjectMapper(),
                    properties,
                    new DelegatedAgentTokenIssuer(properties)
            );
        }
        return new PythonAgentGateway(
                new OkHttpClient(),
                new ObjectMapper(),
                properties,
                new DelegatedAgentTokenIssuer(properties),
                conversationRecorder
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

    private static final class RecordingFailurePublisher implements AgentEventPublisher {

        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final CountDownLatch failed = new CountDownLatch(1);

        @Override
        public void publish(AgentEvent event) {
        }

        @Override
        public void complete() {
        }

        @Override
        public void fail(Throwable throwable) {
            failure.set(throwable);
            failed.countDown();
        }

        private boolean awaitFailure() throws InterruptedException {
            return failed.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class RecordingTerminalPublisher implements AgentEventPublisher {

        private final CountDownLatch terminal = new CountDownLatch(1);

        @Override
        public void publish(AgentEvent event) {
        }

        @Override
        public void complete() {
            terminal.countDown();
        }

        @Override
        public void fail(Throwable throwable) {
            terminal.countDown();
        }

        private boolean awaitTerminal() throws InterruptedException {
            return terminal.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class RecordingConversationRecorder implements AgentConversationRecorder {

        private final AtomicReference<String> answer = new AtomicReference<>();
        private final AtomicReference<ChatMessage.MessageStatus> status = new AtomicReference<>();

        @Override
        public AgentConversationSession begin(AgentRunCommand command) {
            return new AgentConversationSession(command.conversationId(), command.userId(), "question-1", false);
        }

        @Override
        public CompletionPayload complete(
                AgentConversationSession session,
                String answer,
                String thinking,
                List<SourceRef> sources,
                ChatMessage.MessageStatus status
        ) {
            this.answer.set(answer);
            this.status.set(status);
            return new CompletionPayload("answer-1", null, sources, status);
        }
    }
}
