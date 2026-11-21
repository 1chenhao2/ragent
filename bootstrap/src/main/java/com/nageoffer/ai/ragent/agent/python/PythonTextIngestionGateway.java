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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.hutool.core.util.IdUtil;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HTTP adapter for Python text parsing, chunking and embedding computation.
 */
@Component
@ConditionalOnProperty(name = "ragent.agent.runtime", havingValue = "python")
public class PythonTextIngestionGateway implements AgentIngestionGateway {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private final HttpUrl baseUrl;
    private final DelegatedAgentTokenIssuer tokenIssuer;
    private final Duration timeout;
    private final PythonAgentProperties.TextIngestion settings;

    public PythonTextIngestionGateway(
            OkHttpClient httpClient,
            ObjectMapper objectMapper,
            PythonAgentProperties properties,
            DelegatedAgentTokenIssuer tokenIssuer
    ) {
        this.objectMapper = objectMapper;
        this.baseUrl = HttpUrl.get(properties.getBaseUrl());
        this.tokenIssuer = tokenIssuer;
        this.timeout = properties.getRequestTimeout();
        this.settings = properties.getTextIngestion();
        this.client = httpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .readTimeout(timeout)
                .callTimeout(timeout)
                .build();
    }

    @Override
    public boolean supports(String mimeType, String embeddingModelId) {
        return settings.isEnabled()
                && ("text/markdown".equals(mimeType)
                || "text/x-markdown".equals(mimeType)
                || "text/plain".equals(mimeType))
                && settings.getEmbeddingModelId().equals(embeddingModelId);
    }

    @Override
    public boolean fallbackToJava() {
        return settings.isFallbackToJava();
    }

    @Override
    public AgentIngestionResult compute(AgentIngestionRequest request) {
        try {
            if (request.content().length() > settings.getMaxContentChars()) {
                throw new AgentGatewayException(413, "ingestion_content_too_large", "Python text ingestion content is too large");
            }
            String token = tokenIssuer.issue(
                    "system-ingestion",
                    new AgentAccessContext(null, Set.of("system"), Set.of(), Set.of("ingestion:compute"))
            );
            ObjectNode body = objectMapper.createObjectNode()
                    .put("documentName", request.documentName())
                    .put("mimeType", request.mimeType())
                    .put("content", request.content())
                    .put("chunkingMode", request.chunkingMode())
                    .put("embeddingModelId", request.embeddingModelId());
            body.set("chunkConfig", objectMapper.valueToTree(request.chunkConfig()));
            Request httpRequest = new Request.Builder()
                    .url(ingestionUrl())
                    .header("Authorization", "Bearer " + token)
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
            try (Response response = client.newCall(httpRequest).execute()) {
                String responseBody = responseBody(response);
                if (!response.isSuccessful()) {
                    throw remoteFailure(response.code(), responseBody);
                }
                JsonNode root = objectMapper.readTree(responseBody);
                if (!root.isObject() || !root.path("chunks").isArray()) {
                    throw new AgentGatewayException(
                            502,
                            "invalid_agent_response",
                            "Python text ingestion response is missing chunks"
                    );
                }
                JsonNode remoteChunksNode = root.get("chunks");
                validateRemoteChunkShape(remoteChunksNode);
                List<RemoteChunk> remoteChunks = readRemoteChunks(remoteChunksNode);
                List<VectorChunk> chunks = new ArrayList<>(remoteChunks.size());
                int embeddingDimension = -1;
                for (RemoteChunk remoteChunk : remoteChunks) {
                    if (remoteChunk == null) {
                        throw new AgentGatewayException(
                                502,
                                "invalid_agent_response",
                                "Python text ingestion returned a null chunk"
                        );
                    }
                    if (remoteChunk.index() == null || remoteChunk.index() < 0) {
                        throw new AgentGatewayException(
                                502,
                                "invalid_agent_response",
                                "Python text ingestion returned an invalid chunk index"
                        );
                    }
                    if (remoteChunk.content() == null || remoteChunk.content().isBlank()) {
                        throw new AgentGatewayException(
                                502,
                                "invalid_agent_response",
                                "Python text ingestion returned an empty chunk content"
                        );
                    }
                    if (remoteChunk.metadata() == null) {
                        throw new AgentGatewayException(
                                502,
                                "invalid_agent_response",
                                "Python text ingestion returned missing chunk metadata"
                        );
                    }
                    if (remoteChunk.embedding() == null || remoteChunk.embedding().isEmpty()) {
                        throw new AgentGatewayException(
                                502,
                                "invalid_agent_response",
                                "Python text ingestion returned an empty embedding"
                        );
                    }
                    if (embeddingDimension == -1) {
                        embeddingDimension = remoteChunk.embedding().size();
                    } else if (embeddingDimension != remoteChunk.embedding().size()) {
                        throw new AgentGatewayException(
                                502,
                                "invalid_agent_response",
                                "Python text ingestion returned inconsistent embedding dimensions"
                        );
                    }
                    float[] embedding = new float[remoteChunk.embedding().size()];
                    for (int i = 0; i < embedding.length; i++) {
                        Float value = remoteChunk.embedding().get(i);
                        if (value == null || !Float.isFinite(value)) {
                            throw new AgentGatewayException(
                                    502,
                                    "invalid_agent_response",
                                    "Python text ingestion returned a non-finite embedding value"
                            );
                        }
                        embedding[i] = value;
                    }
                    chunks.add(VectorChunk.builder()
                            .chunkId(IdUtil.getSnowflakeNextIdStr())
                            .index(remoteChunk.index())
                            .content(remoteChunk.content())
                            .metadata(remoteChunk.metadata())
                            .embedding(embedding)
                            .build());
                }
                return new AgentIngestionResult(
                        chunks,
                        requiredDuration(root, "parseDurationMs"),
                        requiredDuration(root, "chunkDurationMs"),
                        requiredDuration(root, "embedDurationMs")
                );
            }
        } catch (AgentGatewayException ex) {
            throw ex;
        } catch (JsonProcessingException ex) {
            throw new AgentGatewayException(
                    502,
                    "invalid_agent_response",
                    "Python text ingestion returned malformed JSON",
                    ex
            );
        } catch (IOException ex) {
            throw new AgentGatewayException(0, "agent_service_unavailable", "Unable to compute Python text ingestion", ex);
        }
    }

    private HttpUrl ingestionUrl() {
        return baseUrl.newBuilder()
                .addPathSegments("internal/v1/ingestion/text-chunks")
                .build();
    }

    private String responseBody(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    private AgentGatewayException remoteFailure(int status, String body) {
        try {
            JsonNode problem = objectMapper.readTree(body);
            String code = optionalText(problem, "code");
            String detail = optionalText(problem, "detail");
            String title = optionalText(problem, "title");
            return new AgentGatewayException(
                    status,
                    code == null ? "agent_ingestion_failed" : code,
                    detail == null ? (title == null ? "Python text ingestion failed" : title) : detail
            );
        } catch (IOException ex) {
            return new AgentGatewayException(status, "agent_ingestion_failed", "Python text ingestion failed");
        }
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private List<RemoteChunk> readRemoteChunks(JsonNode chunks) {
        try {
            return objectMapper.convertValue(
                    chunks,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, RemoteChunk.class)
            );
        } catch (IllegalArgumentException ex) {
            throw new AgentGatewayException(
                    502,
                    "invalid_agent_response",
                    "Python text ingestion returned malformed chunks",
                    ex
            );
        }
    }

    private void validateRemoteChunkShape(JsonNode chunks) {
        for (JsonNode chunk : chunks) {
            if (!chunk.isObject()) {
                throw invalidAgentResponse("Python text ingestion returned a non-object chunk");
            }
            if (!chunk.path("index").isIntegralNumber()) {
                throw invalidAgentResponse("Python text ingestion returned an invalid chunk index");
            }
            if (!chunk.path("content").isTextual()) {
                throw invalidAgentResponse("Python text ingestion returned an invalid chunk content");
            }
            JsonNode embedding = chunk.path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) {
                throw invalidAgentResponse("Python text ingestion returned an invalid embedding");
            }
            for (JsonNode value : embedding) {
                if (!value.isNumber()) {
                    throw invalidAgentResponse("Python text ingestion returned a non-numeric embedding value");
                }
            }
            if (!chunk.path("metadata").isObject()) {
                throw invalidAgentResponse("Python text ingestion returned invalid chunk metadata");
            }
        }
    }

    private AgentGatewayException invalidAgentResponse(String message) {
        return new AgentGatewayException(502, "invalid_agent_response", message);
    }

    private long requiredDuration(JsonNode root, String field) {
        JsonNode timings = root.get("timings");
        JsonNode value = timings == null ? null : timings.get(field);
        if (timings == null || !timings.isObject() || value == null || !value.isIntegralNumber() || value.longValue() < 0) {
            throw new AgentGatewayException(
                    502,
                    "invalid_agent_response",
                    "Python text ingestion returned an invalid timing: " + field
            );
        }
        return value.longValue();
    }

    private record RemoteChunk(
            Integer index,
            String content,
            List<Float> embedding,
            Map<String, Object> metadata
    ) {
    }
}
