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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentGatewayExceptionTest {

    @Test
    void shouldAllowJavaFallbackForUnavailableAgentService() {
        assertTrue(new AgentGatewayException(0, "agent_service_unavailable", "unavailable").isFallbackEligible());
    }

    @Test
    void shouldAllowJavaFallbackForEmbeddingProviderFailure() {
        assertTrue(new AgentGatewayException(502, "embedding_provider_failed", "provider failed").isFallbackEligible());
    }

    @Test
    void shouldNotAllowJavaFallbackForInvalidRemoteResponse() {
        assertFalse(new AgentGatewayException(502, "invalid_agent_response", "malformed").isFallbackEligible());
    }

    @Test
    void shouldNotAllowJavaFallbackForInvalidIngestionRequest() {
        assertFalse(new AgentGatewayException(400, "invalid_ingestion_request", "invalid").isFallbackEligible());
    }
}
