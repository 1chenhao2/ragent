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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.agent.event.AgentEvent;
import com.nageoffer.ai.ragent.agent.event.AgentEventPublisher;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunHandle;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage.MessageStatus;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
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
    private final ConcurrentMap<String, RemoteRun> activeRuns = new ConcurrentHashMap<>();

    public PythonAgentGateway(
            @Qualifier("streamingHttpClient") OkHttpClient httpClient,
            ObjectMapper objectMapper,
            PythonAgentProperties properties,
            DelegatedAgentTokenIssuer tokenIssuer
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = parseBaseUrl(properties.getBaseUrl());
        this.tokenIssuer = tokenIssuer;
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

    @Override
    public AgentRunHandle start(AgentRunCommand command, AgentEventPublisher publisher) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(publisher, "publisher");
        String token = tokenIssuer.issue(command);
        AcceptedRun accepted = createRun(command, token);
        RemoteRun run = new RemoteRun(accepted.runId(), command.conversationId(), token, publisher);
        if (activeRuns.putIfAbsent(run.runId, run) != null) {
            throw new AgentGatewayException(409, "duplicate_run_id", "Agent run already exists: " + run.runId);
        }

        Request streamRequest = authorizedRequest(eventStreamUrl(run.runId), token).get().build();
        EventSource eventSource = eventSourceFactory.newEventSource(streamRequest, new PythonEventListener(run));
        run.eventSource.set(eventSource);
        publisher.onClose(() -> closeClientRun(run));
        return new AgentRunHandle(run.runId, command.conversationId());
    }

    @Override
    public void cancel(String runId) {
        RemoteRun run = activeRuns.get(runId);
        if (run == null) {
            return;
        }
        cancelRemoteRun(run);
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
        Request request = authorizedRequest(cancelUrl(run.runId), run.token)
                .header("Idempotency-Key", run.runId + "-cancel-request")
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

    private void closeClientRun(RemoteRun run) {
        if (!activeRuns.remove(run.runId, run) || !run.terminal.compareAndSet(false, true)) {
            return;
        }
        try {
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
        if (!run.terminal.compareAndSet(false, true)) {
            return;
        }
        activeRuns.remove(run.runId, run);
        run.publisher.publish(event);
        run.publisher.complete();
    }

    private void fail(RemoteRun run, Throwable throwable) {
        if (!run.terminal.compareAndSet(false, true)) {
            return;
        }
        activeRuns.remove(run.runId, run);
        run.publisher.fail(throwable);
    }

    private AgentEvent mapCompleted(RemoteRun run, JsonNode data) {
        List<SourceRef> sources = sources(data);
        if (sources == null) {
            sources = run.sources;
        }
        return AgentEvent.completed(new CompletionPayload(
                optionalText(data, "messageId"),
                optionalText(data, "title"),
                sources,
                MessageStatus.NORMAL
        ));
    }

    private AgentEvent mapCancelled(RemoteRun run, JsonNode data) {
        List<SourceRef> sources = sources(data);
        if (sources == null) {
            sources = run.sources;
        }
        return AgentEvent.cancelled(new CompletionPayload(
                optionalText(data, "messageId"),
                optionalText(data, "title"),
                sources,
                MessageStatus.INTERRUPTED
        ));
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

    private final class PythonEventListener extends EventSourceListener {

        private final RemoteRun run;

        private PythonEventListener(RemoteRun run) {
            this.run = run;
        }

        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
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
                    case "thinking_delta" -> run.publisher.publish(AgentEvent.thinkingDelta(requiredText(payload, "delta")));
                    case "answer_delta" -> run.publisher.publish(AgentEvent.answerDelta(
                            new MessageDelta("response", requiredText(payload, "delta"))
                    ));
                    case "sources" -> run.sources = sources(payload);
                    case "run_completed" -> publishTerminal(run, mapCompleted(run, payload));
                    case "run_cancelled" -> publishTerminal(run, mapCancelled(run, payload));
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

        @Override
        public void onClosed(EventSource eventSource) {
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
        private final AgentEventPublisher publisher;
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final AtomicReference<EventSource> eventSource = new AtomicReference<>();
        private volatile List<SourceRef> sources;

        private RemoteRun(String runId, String conversationId, String token, AgentEventPublisher publisher) {
            this.runId = runId;
            this.conversationId = conversationId;
            this.token = token;
            this.publisher = publisher;
        }
    }
}
