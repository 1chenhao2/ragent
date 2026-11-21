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

import cn.hutool.core.thread.ThreadFactoryBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.agent.conversation.AgentConversationRecorder;
import com.nageoffer.ai.ragent.agent.conversation.AgentConversationSession;
import com.nageoffer.ai.ragent.agent.event.AgentEvent;
import com.nageoffer.ai.ragent.agent.event.AgentEventPublisher;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentApprovalDecision;
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunHandle;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage.MessageStatus;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP/SSE adapter for the Python Agent runtime.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ragent.agent.runtime", havingValue = "python")
public class PythonAgentGateway implements AgentGateway {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient requestClient;
    private final EventSource.Factory eventSourceFactory;
    private final ObjectMapper objectMapper;
    private final HttpUrl baseUrl;
    private final DelegatedAgentTokenIssuer tokenIssuer;
    private final AgentConversationRecorder conversationRecorder;
    private final long runTimeoutMillis;
    private final ScheduledExecutorService deadlineScheduler = Executors.newSingleThreadScheduledExecutor(
            ThreadFactoryBuilder.create()
                    .setNamePrefix("python_agent_deadline_")
                    .setDaemon(true)
                    .build()
    );
    private final ConcurrentMap<String, RemoteRun> activeRuns = new ConcurrentHashMap<>();

    @Autowired
    public PythonAgentGateway(
            @Qualifier("streamingHttpClient") OkHttpClient httpClient,
            ObjectMapper objectMapper,
            PythonAgentProperties properties,
            DelegatedAgentTokenIssuer tokenIssuer,
            AgentConversationRecorder conversationRecorder
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = parseBaseUrl(properties.getBaseUrl());
        this.tokenIssuer = tokenIssuer;
        this.conversationRecorder = conversationRecorder;
        this.runTimeoutMillis = requirePositiveTimeout(properties.getRunTimeout());
        requireTokenCoversRunTimeout(properties.getDelegatedToken().getTtl(), runTimeoutMillis);
        this.requestClient = httpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .readTimeout(properties.getRequestTimeout())
                .callTimeout(properties.getRequestTimeout())
                .build();
        OkHttpClient streamingClient = httpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .readTimeout(properties.getStreamReadTimeout())
                .callTimeout(java.time.Duration.ZERO)
                .build();
        this.eventSourceFactory = EventSources.createFactory(streamingClient);
    }

    PythonAgentGateway(
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            PythonAgentProperties properties,
            DelegatedAgentTokenIssuer tokenIssuer
    ) {
        this(httpClient, objectMapper, properties, tokenIssuer, new NoOpAgentConversationRecorder());
    }

    @Override
    public AgentRunHandle start(AgentRunCommand command, AgentEventPublisher publisher) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(publisher, "publisher");
        String token = tokenIssuer.issue(command);
        AcceptedRun accepted = createRun(command, token);
        AgentConversationSession conversation;
        try {
            conversation = conversationRecorder.begin(command);
        } catch (RuntimeException ex) {
            try {
                cancelRemoteRun(accepted.runId(), token);
            } catch (RuntimeException cancelError) {
                ex.addSuppressed(cancelError);
            }
            throw ex;
        }
        RemoteRun run = new RemoteRun(accepted.runId(), command.conversationId(), token, command, publisher, conversation);
        if (activeRuns.putIfAbsent(run.runId, run) != null) {
            throw new AgentGatewayException(409, "duplicate_run_id", "Agent run already exists: " + run.runId);
        }

        Request streamRequest = authorizedRequest(eventStreamUrl(run.runId), token).get().build();
        EventSource eventSource = eventSourceFactory.newEventSource(streamRequest, new PythonEventListener(run));
        run.eventSource.set(eventSource);
        scheduleDeadline(run);
        publisher.onClose(() -> closeClientRun(run));
        return new AgentRunHandle(run.runId, command.conversationId());
    }

    @PreDestroy
    public void close() {
        deadlineScheduler.shutdownNow();
    }

    @Override
    public void cancel(String runId) {
        RemoteRun run = activeRuns.get(runId);
        if (run == null) {
            return;
        }
        cancelRemoteRun(run);
    }

    @Override
    public AgentRunHandle resume(String runId, AgentApprovalDecision decision) {
        Objects.requireNonNull(decision, "decision");
        RemoteRun run = activeRuns.get(runId);
        if (run == null) {
            throw new AgentGatewayException(409, "agent_run_not_active", "Python Agent run is no longer active");
        }
        if (!Objects.equals(run.waitingApprovalId.get(), decision.approvalId())) {
            throw new AgentGatewayException(409, "approval_id_mismatch", "Approval does not match the paused Agent run");
        }
        String token = tokenIssuer.issue(run.command);
        resumeRemoteRun(runId, decision, token);
        if (run.waitingApprovalId.compareAndSet(decision.approvalId(), null)) {
            scheduleDeadline(run);
        }
        return new AgentRunHandle(runId, run.conversationId);
    }

    private AcceptedRun createRun(AgentRunCommand command, String token) {
        Request request;
        try {
            String body = objectMapper.writeValueAsString(createRequest(command));
            request = authorizedRequest(createRunUrl(), token)
                    .header("Idempotency-Key", command.requestId())
                    .post(RequestBody.create(body, JSON))
                    .build();
        } catch (JsonProcessingException ex) {
            throw new AgentGatewayException(0, "agent_request_serialization_failed", "Unable to serialize Agent run", ex);
        }

        try (Response response = requestClient.newCall(request).execute()) {
            String body = responseBody(response);
            if (response.code() != 202) {
                throw remoteFailure(response.code(), body);
            }
            JsonNode accepted = objectMapper.readTree(body);
            String runId = requiredText(accepted, "runId");
            requiredText(accepted, "eventStreamUrl");
            return new AcceptedRun(runId);
        } catch (AgentGatewayException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AgentGatewayException(
                    0,
                    "agent_service_unavailable",
                    "Unable to create Python Agent run",
                    ex
            );
        }
    }

    private ObjectNode createRequest(AgentRunCommand command) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("requestId", command.requestId());
        request.put("conversationId", command.conversationId());
        request.put("agentType", command.agentType().name().toLowerCase(Locale.ROOT));
        request.put("input", command.input());

        AgentAccessContext accessContext = command.access();
        ObjectNode access = request.putObject("access");
        if (accessContext.tenantId() == null) {
            access.putNull("tenantId");
        } else {
            access.put("tenantId", accessContext.tenantId());
        }
        access.put("userId", command.userId());
        putSorted(access, "roles", accessContext.roles());
        putSorted(access, "allowedKnowledgeBaseIds", accessContext.allowedKnowledgeBaseIds());
        putSorted(access, "toolScopes", accessContext.toolScopes());

        request.putObject("options")
                .put("deepThinking", command.deepThinking())
                .put("locale", "zh-CN");
        request.putObject("metadata").put("javaRunId", command.runId());
        return request;
    }

    private void putSorted(ObjectNode parent, String field, Set<String> values) {
        ArrayNode array = parent.putArray(field);
        values.stream().sorted().forEach(array::add);
    }

    private void cancelRemoteRun(RemoteRun run) {
        cancelRemoteRun(run.runId, run.token);
    }

    private void cancelRemoteRun(String runId, String token) {
        Request request = authorizedRequest(cancelUrl(runId), token)
                .header("Idempotency-Key", runId + "-cancel-request")
                .post(RequestBody.create(new byte[0], null))
                .build();
        try (Response response = requestClient.newCall(request).execute()) {
            String body = responseBody(response);
            if (response.code() != 202) {
                throw remoteFailure(response.code(), body);
            }
        } catch (AgentGatewayException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AgentGatewayException(
                    0,
                    "agent_service_unavailable",
                    "Unable to cancel Python Agent run",
                    ex
            );
        }
    }

    private void resumeRemoteRun(String runId, AgentApprovalDecision decision, String token) {
        ObjectNode body = objectMapper.createObjectNode()
                .put("approvalId", decision.approvalId())
                .put("decision", decision.decision());
        if (decision.input() != null) {
            body.put("input", decision.input());
        }
        Request request = authorizedRequest(resumeUrl(runId), token)
                .header("Idempotency-Key", runId + "-resume-" + decision.approvalId())
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = requestClient.newCall(request).execute()) {
            String responseBody = responseBody(response);
            if (response.code() != 202) {
                throw remoteFailure(response.code(), responseBody);
            }
        } catch (AgentGatewayException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new AgentGatewayException(0, "agent_service_unavailable", "Unable to resume Python Agent run", ex);
        }
    }

    private void closeClientRun(RemoteRun run) {
        if (!claimTerminal(run)) {
            return;
        }
        try {
            recordInterrupted(run);
            cancelRemoteRun(run);
        } catch (RuntimeException ex) {
            log.warn("Failed to cancel Python Agent run after client stream closed, runId={}", run.runId, ex);
        } finally {
            EventSource eventSource = run.eventSource.get();
            if (eventSource != null) {
                eventSource.cancel();
            }
        }
    }

    private void publishTerminal(RemoteRun run, AgentEvent event) {
        if (!claimTerminal(run)) {
            return;
        }
        recordInterrupted(run);
        run.publisher.publish(event);
        run.publisher.complete();
    }

    private void completeRun(RemoteRun run, MessageStatus status) {
        if (!claimTerminal(run)) {
            return;
        }
        try {
            run.publisher.publish(recordCompletion(run, status));
            run.publisher.complete();
        } catch (RuntimeException ex) {
            run.publisher.fail(ex);
        }
    }

    private void pauseForApproval(RemoteRun run, String approvalId) {
        ScheduledFuture<?> deadline = run.deadline.getAndSet(null);
        if (deadline != null) {
            deadline.cancel(false);
        }
        if (run.waitingApprovalId.compareAndSet(null, approvalId)) {
            run.publisher.publish(AgentEvent.approvalRequired(approvalId));
        }
    }

    private void recordInterrupted(RemoteRun run) {
        if (run.answer.isEmpty()) {
            return;
        }
        try {
            conversationRecorder.complete(
                    run.conversation,
                    run.answer.toString(),
                    run.thinking.toString(),
                    run.sources,
                    MessageStatus.INTERRUPTED
            );
        } catch (RuntimeException ex) {
            log.warn("Failed to persist interrupted Python Agent answer, runId={}", run.runId, ex);
        }
    }

    private void fail(RemoteRun run, Throwable throwable) {
        if (!claimTerminal(run)) {
            return;
        }
        recordInterrupted(run);
        run.publisher.fail(throwable);
    }

    private void scheduleDeadline(RemoteRun run) {
        ScheduledFuture<?> deadline = deadlineScheduler.schedule(
                () -> expireRun(run),
                runTimeoutMillis,
                TimeUnit.MILLISECONDS
        );
        run.deadline.set(deadline);
        if (run.terminal.get()) {
            deadline.cancel(false);
        }
    }

    private void expireRun(RemoteRun run) {
        if (!claimTerminal(run)) {
            return;
        }
        recordInterrupted(run);
        EventSource eventSource = run.eventSource.get();
        if (eventSource != null) {
            eventSource.cancel();
        }
        run.publisher.fail(new AgentGatewayException(
                504,
                "agent_run_timeout",
                "Python Agent run exceeded its deadline"
        ));
        try {
            cancelRemoteRun(run);
        } catch (RuntimeException ex) {
            log.warn("Failed to cancel timed out Python Agent run, runId={}", run.runId, ex);
        }
    }

    private void cancelDeadline(RemoteRun run) {
        ScheduledFuture<?> deadline = run.deadline.get();
        if (deadline != null) {
            deadline.cancel(false);
        }
    }

    private boolean claimTerminal(RemoteRun run) {
        synchronized (run) {
            if (!run.terminal.compareAndSet(false, true)) {
                return false;
            }
            activeRuns.remove(run.runId, run);
            cancelDeadline(run);
            return true;
        }
    }

    private AgentEvent recordCompletion(RemoteRun run, MessageStatus status) {
        CompletionPayload payload = conversationRecorder.complete(
                run.conversation,
                run.answer.toString(),
                run.thinking.toString(),
                run.sources,
                status
        );
        return status == MessageStatus.INTERRUPTED
                ? AgentEvent.cancelled(payload)
                : AgentEvent.completed(payload);
    }

    private List<SourceRef> sources(JsonNode data) {
        JsonNode sources = data.get("sources");
        if (sources == null || !sources.isArray()) {
            return null;
        }
        return objectMapper.convertValue(
                sources,
                objectMapper.getTypeFactory().constructCollectionType(List.class, SourceRef.class)
        );
    }

    private String failureMessage(JsonNode data) {
        JsonNode error = data.path("error");
        String detail = optionalText(error, "detail");
        if (detail != null) {
            return detail;
        }
        String title = optionalText(error, "title");
        if (title != null) {
            return title;
        }
        String code = optionalText(error, "code");
        return code == null ? "Python Agent run failed" : code;
    }

    private Request.Builder authorizedRequest(HttpUrl url, String token) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + token);
    }

    private HttpUrl createRunUrl() {
        return baseUrl.newBuilder()
                .addPathSegments("internal/v1/agent-runs")
                .build();
    }

    private HttpUrl eventStreamUrl(String runId) {
        return baseUrl.newBuilder()
                .addPathSegments("internal/v1/agent-runs")
                .addPathSegment(runId)
                .addPathSegment("events")
                .build();
    }

    private HttpUrl cancelUrl(String runId) {
        return baseUrl.newBuilder()
                .addPathSegments("internal/v1/agent-runs")
                .addPathSegment(runId)
                .addPathSegment("cancel")
                .build();
    }

    private HttpUrl resumeUrl(String runId) {
        return baseUrl.newBuilder()
                .addPathSegments("internal/v1/agent-runs")
                .addPathSegment(runId)
                .addPathSegment("resume")
                .build();
    }

    private AgentGatewayException remoteFailure(int status, String body) {
        try {
            JsonNode problem = objectMapper.readTree(body);
            String code = optionalText(problem, "code");
            String detail = optionalText(problem, "detail");
            String title = optionalText(problem, "title");
            return new AgentGatewayException(
                    status,
                    code == null ? "agent_service_error" : code,
                    detail == null ? (title == null ? "Python Agent service rejected the request" : title) : detail
            );
        } catch (JsonProcessingException ex) {
            return new AgentGatewayException(status, "agent_service_error", "Python Agent service rejected the request");
        }
    }

    private String responseBody(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new AgentGatewayException(502, "invalid_agent_response", "Missing Agent response field: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()
                ? null
                : value.asText();
    }

    private static HttpUrl parseBaseUrl(String value) {
        try {
            HttpUrl url = HttpUrl.get(value);
            if (!"http".equals(url.scheme()) && !"https".equals(url.scheme())) {
                throw new IllegalArgumentException("unsupported scheme");
            }
            return url;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid ragent.agent.python.base-url", ex);
        }
    }

    private static long requirePositiveTimeout(java.time.Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("ragent.agent.python.run-timeout must be positive");
        }
        return Math.max(1, timeout.toMillis());
    }

    private static void requireTokenCoversRunTimeout(java.time.Duration ttl, long runTimeoutMillis) {
        if (ttl == null || ttl.toMillis() <= runTimeoutMillis) {
            throw new IllegalStateException("ragent.agent.python.delegated-token.ttl must exceed run-timeout");
        }
    }

    private final class PythonEventListener extends EventSourceListener {

        private final RemoteRun run;

        private PythonEventListener(RemoteRun run) {
            this.run = run;
        }

        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            synchronized (run) {
                if (run.terminal.get()) {
                    return;
                }
                try {
                    JsonNode envelope = objectMapper.readTree(data);
                    if (!run.runId.equals(requiredText(envelope, "runId"))) {
                        throw new AgentGatewayException(502, "invalid_agent_event", "Agent event runId does not match");
                    }
                    JsonNode payload = envelope.path("data");
                    switch (requiredText(envelope, "type")) {
                        case "run_started" -> run.publisher.publish(AgentEvent.runStarted(run.conversationId, run.runId));
                        case "thinking_delta" -> {
                            String delta = requiredText(payload, "delta");
                            run.thinking.append(delta);
                            run.publisher.publish(AgentEvent.thinkingDelta(delta));
                        }
                        case "answer_delta" -> {
                            String delta = requiredText(payload, "delta");
                            run.answer.append(delta);
                            run.publisher.publish(AgentEvent.answerDelta(new MessageDelta("response", delta)));
                        }
                        case "sources" -> run.sources = sources(payload);
                        case "approval_required" -> pauseForApproval(run, requiredText(payload, "approvalId"));
                        case "run_completed" -> completeRun(run, MessageStatus.NORMAL);
                        case "run_cancelled" -> completeRun(run, MessageStatus.INTERRUPTED);
                        case "run_failed" -> publishTerminal(run, AgentEvent.rejected(failureMessage(payload)));
                        default -> {
                            // Tool, approval and heartbeat events have no legacy browser equivalent.
                        }
                    }
                } catch (RuntimeException | JsonProcessingException ex) {
                    eventSource.cancel();
                    fail(run, ex);
                }
            }
        }

        @Override
        public void onClosed(EventSource eventSource) {
            if (run.waitingApprovalId.get() != null) {
                return;
            }
            fail(run, new AgentGatewayException(502, "agent_stream_closed", "Agent stream closed before a terminal event"));
        }

        @Override
        public void onFailure(EventSource eventSource, Throwable throwable, Response response) {
            if (run.terminal.get()) {
                return;
            }
            if (response != null) {
                try {
                    fail(run, remoteFailure(response.code(), responseBody(response)));
                    return;
                } catch (IOException ex) {
                    fail(run, ex);
                    return;
                }
            }
            fail(run, new AgentGatewayException(
                    0,
                    "agent_stream_unavailable",
                    "Python Agent event stream failed",
                    throwable
            ));
        }
    }

    private record AcceptedRun(String runId) {
    }

    private static final class RemoteRun {

        private final String runId;
        private final String conversationId;
        private final String token;
        private final AgentRunCommand command;
        private final AgentEventPublisher publisher;
        private final AgentConversationSession conversation;
        private final StringBuilder answer = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final AtomicReference<EventSource> eventSource = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> deadline = new AtomicReference<>();
        private final AtomicReference<String> waitingApprovalId = new AtomicReference<>();
        private volatile List<SourceRef> sources;

        private RemoteRun(
                String runId,
                String conversationId,
                String token,
                AgentRunCommand command,
                AgentEventPublisher publisher,
                AgentConversationSession conversation
        ) {
            this.runId = runId;
            this.conversationId = conversationId;
            this.token = token;
            this.command = command;
            this.publisher = publisher;
            this.conversation = conversation;
        }
    }

    private static final class NoOpAgentConversationRecorder implements AgentConversationRecorder {

        @Override
        public AgentConversationSession begin(AgentRunCommand command) {
            return new AgentConversationSession(command.conversationId(), command.userId(), null, false);
        }

        @Override
        public CompletionPayload complete(
                AgentConversationSession session,
                String answer,
                String thinking,
                List<SourceRef> sources,
                MessageStatus status
        ) {
            return new CompletionPayload(null, null, sources, status);
        }
    }
}
