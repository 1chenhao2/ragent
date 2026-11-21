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

package com.nageoffer.ai.ragent.agent.legacy;

import com.nageoffer.ai.ragent.agent.event.AgentEventPublisher;
import com.nageoffer.ai.ragent.agent.gateway.AgentApprovalDecision;
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunHandle;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatContext;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatPipeline;
import com.nageoffer.ai.ragent.rag.service.ratelimit.ChatQueueLimiter;
import com.nageoffer.ai.ragent.rag.trace.StreamChatTraceRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adapter for the current Java RAG runtime.
 */
@Component
@ConditionalOnProperty(name = "ragent.agent.runtime", havingValue = "legacy", matchIfMissing = true)
@RequiredArgsConstructor
public class LegacyRagAgentGateway implements AgentGateway {

    private final StreamChatPipeline chatPipeline;
    private final ChatQueueLimiter chatQueueLimiter;
    private final StreamCallbackFactory callbackFactory;
    private final StreamChatTraceRunner traceRunner;
    private final StreamTaskManager taskManager;

    @Override
    public AgentRunHandle start(AgentRunCommand command, AgentEventPublisher publisher) {
        StreamCallback callback = callbackFactory.createChatEventHandler(
                command.conversationId(),
                command.runId(),
                command.userId(),
                publisher
        );
        chatQueueLimiter.enqueue(
                command.input(),
                command.conversationId(),
                command.userId(),
                publisher,
                () -> traceRunner.run(
                        command.input(),
                        command.conversationId(),
                        command.runId(),
                        command.userId(),
                        callback,
                        traceAware -> chatPipeline.execute(StreamChatContext.builder()
                                .question(command.input())
                                .conversationId(command.conversationId())
                                .taskId(command.runId())
                                .deepThinking(command.deepThinking())
                                .userId(command.userId())
                                .callback(traceAware)
                                .build())
                )
        );
        return new AgentRunHandle(command.runId(), command.conversationId());
    }

    @Override
    public void cancel(String runId) {
        taskManager.cancel(runId);
    }

    @Override
    public AgentRunHandle resume(String runId, AgentApprovalDecision decision) {
        throw new UnsupportedOperationException("Legacy Agent runtime does not support workflow approval resume");
    }
}
