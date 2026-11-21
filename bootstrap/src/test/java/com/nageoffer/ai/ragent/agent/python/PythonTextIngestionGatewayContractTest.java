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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PythonTextIngestionGatewayContractTest {

    private static final String SECRET = "test-delegated-token-secret-0123456789";

    private MockWebServer server;
    private AgentIngestionGateway gateway;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        objectMapper = new ObjectMapper();
        PythonAgentProperties properties = new PythonAgentProperties();
        properties.setBaseUrl(server.url("/").toString());
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.getDelegatedToken().setSecret(SECRET);
        gateway = new PythonTextIngestionGateway(
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
    void shouldDelegateTextComputationAndMapEmbeddedChunks() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "chunks": [{
                            "index": 0,
                            "content": "# Handbook\\n\\nEscalate incidents.",
                            "embedding": [0.25, 0.75],
                            "metadata": {"outlinePath": ["Handbook"]}
                          }],
                          "timings": {"parseDurationMs": 3, "chunkDurationMs": 5, "embedDurationMs": 7}
                        }
                        """));

        AgentIngestionGateway.AgentIngestionResult result = gateway.compute(
                new AgentIngestionGateway.AgentIngestionRequest(
                        "handbook.md",
                        "text/markdown",
                        "# Handbook\\n\\nEscalate incidents.",
                        "structure_aware",
                        Map.of("targetChars", 240),
                        "qwen-emb-8b"
                )
        );

        assertEquals(1, result.chunks().size());
        assertEquals("# Handbook\n\nEscalate incidents.", result.chunks().getFirst().getContent());
        assertArrayEquals(new float[]{0.25F, 0.75F}, result.chunks().getFirst().getEmbedding());
        assertEquals(3, result.parseDuration());
        assertEquals(5, result.chunkDuration());
        assertEquals(7, result.embedDuration());

        RecordedRequest request = server.takeRequest();
        assertEquals("/internal/v1/ingestion/text-chunks", request.getPath());
        JsonNode body = objectMapper.readTree(request.getBody().readUtf8());
        assertEquals("handbook.md", body.at("/documentName").asText());
        assertEquals("structure_aware", body.at("/chunkingMode").asText());
        assertEquals(240, body.at("/chunkConfig/targetChars").asInt());
        assertEquals("qwen-emb-8b", body.at("/embeddingModelId").asText());
        String token = request.getHeader("Authorization").substring("Bearer ".length());
        assertEquals(
                "system-ingestion",
                JWT.require(Algorithm.HMAC256(SECRET))
                        .withIssuer("ragent-java")
                        .withAudience("ragent-agent-service")
                        .build()
                        .verify(token)
                        .getSubject()
        );
    }

    @Test
    void shouldRejectRemoteChunkWithoutIndex() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "chunks": [{
                            "content": "Escalate incidents.",
                            "embedding": [0.25, 0.75],
                            "metadata": {}
                          }],
                          "timings": {"parseDurationMs": 3, "chunkDurationMs": 5, "embedDurationMs": 7}
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    @Test
    void shouldRejectRemoteChunkWithNonIntegerIndex() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "chunks": [{
                            "index": "0",
                            "content": "Escalate incidents.",
                            "embedding": [0.25, 0.75],
                            "metadata": {}
                          }],
                          "timings": {"parseDurationMs": 3, "chunkDurationMs": 5, "embedDurationMs": 7}
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    @Test
    void shouldRejectNullRemoteChunk() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "chunks": [null],
                          "timings": {"parseDurationMs": 3, "chunkDurationMs": 5, "embedDurationMs": 7}
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    @Test
    void shouldRejectRemoteChunkWithoutContent() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "chunks": [{
                            "index": 0,
                            "content": null,
                            "embedding": [0.25, 0.75],
                            "metadata": {}
                          }],
                          "timings": {"parseDurationMs": 3, "chunkDurationMs": 5, "embedDurationMs": 7}
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    @Test
    void shouldRejectRemoteChunkWithNonFiniteEmbeddingValue() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "chunks": [{
                            "index": 0,
                            "content": "Escalate incidents.",
                            "embedding": [0.25, 1e39],
                            "metadata": {}
                          }],
                          "timings": {"parseDurationMs": 3, "chunkDurationMs": 5, "embedDurationMs": 7}
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    @Test
    void shouldRejectRemoteChunkWithNonArrayEmbedding() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "chunks": [{
                            "index": 0,
                            "content": "Escalate incidents.",
                            "embedding": "0.25",
                            "metadata": {}
                          }],
                          "timings": {"parseDurationMs": 3, "chunkDurationMs": 5, "embedDurationMs": 7}
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    @Test
    void shouldRejectRemoteChunksWithMismatchedEmbeddingDimensions() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "chunks": [
                            {
                              "index": 0,
                              "content": "Escalate incidents.",
                              "embedding": [0.25, 0.75],
                              "metadata": {}
                            },
                            {
                              "index": 1,
                              "content": "Notify the on-call engineer.",
                              "embedding": [0.10, 0.20, 0.30],
                              "metadata": {}
                            }
                          ],
                          "timings": {"parseDurationMs": 3, "chunkDurationMs": 5, "embedDurationMs": 7}
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    @Test
    void shouldPreserveStableProblemCodeFromEmbeddingProviderFailure() {
        server.enqueue(new MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "application/problem+json")
                .setBody("""
                        {
                          "title": "Bad Gateway",
                          "status": 502,
                          "code": "embedding_provider_failed"
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("embedding_provider_failed", error.getCode());
    }

    @Test
    void shouldRejectRemoteResponseWithoutChunks() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "timings": {"parseDurationMs": 3, "chunkDurationMs": 5, "embedDurationMs": 7}
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    @Test
    void shouldRejectMalformedRemoteResponse() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"chunks\":"));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    @Test
    void shouldRejectRemoteResponseWithoutTimings() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "chunks": [{
                            "index": 0,
                            "content": "Escalate incidents.",
                            "embedding": [0.25, 0.75],
                            "metadata": {}
                          }]
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    @Test
    void shouldRejectRemoteChunkWithoutMetadata() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "chunks": [{
                            "index": 0,
                            "content": "Escalate incidents.",
                            "embedding": [0.25, 0.75]
                          }],
                          "timings": {"parseDurationMs": 3, "chunkDurationMs": 5, "embedDurationMs": 7}
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    @Test
    void shouldRejectRemoteChunkWithNonObjectMetadata() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "chunks": [{
                            "index": 0,
                            "content": "Escalate incidents.",
                            "embedding": [0.25, 0.75],
                            "metadata": "not-an-object"
                          }],
                          "timings": {"parseDurationMs": 3, "chunkDurationMs": 5, "embedDurationMs": 7}
                        }
                        """));

        AgentGatewayException error = assertThrows(
                AgentGatewayException.class,
                () -> gateway.compute(request())
        );

        assertEquals(502, error.getStatus());
        assertEquals("invalid_agent_response", error.getCode());
    }

    private AgentIngestionGateway.AgentIngestionRequest request() {
        return new AgentIngestionGateway.AgentIngestionRequest(
                "handbook.md",
                "text/markdown",
                "# Handbook\n\nEscalate incidents.",
                "structure_aware",
                Map.of("targetChars", 240),
                "qwen-emb-8b"
        );
    }
}
