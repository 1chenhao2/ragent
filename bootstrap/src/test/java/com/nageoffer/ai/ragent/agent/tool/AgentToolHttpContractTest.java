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

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.nageoffer.ai.ragent.agent.python.PythonAgentProperties;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentToolHttpContractTest {

    private static final String SECRET = "test-delegated-token-secret-0123456789";

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PythonAgentProperties properties = new PythonAgentProperties();
        properties.getDelegatedToken().setSecret(SECRET);
        AgentToolGateway gateway = new AuthorizedAgentToolGateway(
                List.of(new KnowledgeBaseListAgentTool(knowledgeBaseMapper))
        );
        AgentToolController controller = new AgentToolController(
                new AgentToolTokenVerifier(properties),
                gateway
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentToolExceptionHandler())
                .build();
    }

    @Test
    void shouldInvokeAuthorizedBusinessToolOverInternalHttp() throws Exception {
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of(
                KnowledgeBaseDO.builder()
                        .id("knowledge-1")
                        .name("Operations")
                        .collectionName("operations")
                        .embeddingModel("qwen-emb-8b")
                        .deleted(0)
                        .build()
        ));

        mockMvc.perform(post("/internal/v1/agent-tools/invoke")
                        .header("Authorization", "Bearer " + token("knowledge:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolName": "knowledge_base.list",
                                  "toolCallId": "tool-call-00000001",
                                  "idempotencyKey": "idempotency-key-0001",
                                  "arguments": {"knowledgeBaseIds": ["knowledge-1"]}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolCallId").value("tool-call-00000001"))
                .andExpect(jsonPath("$.toolName").value("knowledge_base.list"))
                .andExpect(jsonPath("$.output.knowledgeBases[0].id").value("knowledge-1"))
                .andExpect(jsonPath("$.output.knowledgeBases[0].name").value("Operations"));
    }

    @Test
    void shouldReturnProblemDetailsWhenToolScopeIsMissing() throws Exception {
        mockMvc.perform(post("/internal/v1/agent-tools/invoke")
                        .header("Authorization", "Bearer " + token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toolName": "knowledge_base.list",
                                  "toolCallId": "tool-call-00000002",
                                  "idempotencyKey": "idempotency-key-0002",
                                  "arguments": {"knowledgeBaseIds": ["knowledge-1"]}
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.code").value("tool_scope_forbidden"));
    }

    private String token(String... scopes) {
        Instant now = Instant.now();
        return JWT.create()
                .withSubject("user-1")
                .withIssuer("ragent-java")
                .withAudience("ragent-agent-service", "ragent-business-tools")
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plusSeconds(300)))
                .withArrayClaim("roles", new String[]{"user"})
                .withArrayClaim("allowedKnowledgeBaseIds", new String[]{"knowledge-1"})
                .withArrayClaim("toolScopes", scopes)
                .sign(Algorithm.HMAC256(SECRET));
    }
}
