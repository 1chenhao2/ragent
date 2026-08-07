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
import com.nageoffer.ai.ragent.agent.gateway.AgentGateway;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunHandle;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatContext;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatPipeline;
import com.nageoffer.ai.ragent.rag.service.ratelimit.ChatQueueLimiter;
import com.nageoffer.ai.ragent.rag.trace.StreamChatTraceRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegacyRagAgentGatewayTest {

    private static final AgentRunCommand COMMAND = new AgentRunCommand(
            "request-1",
            "run-1",
            "conversation-1",
            "user-1",
            AgentType.KNOWLEDGE,
            "question",
            true
    );

    @Mock
    private StreamChatPipeline chatPipeline;

    @Mock
    private ChatQueueLimiter chatQueueLimiter;

    @Mock
    private StreamCallbackFactory callbackFactory;

    @Mock
    private StreamChatTraceRunner traceRunner;

    @Mock
    private StreamTaskManager taskManager;

    @Mock
    private StreamCallback callback;

    @Mock
    private AgentEventPublisher publisher;

    private AgentGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new LegacyRagAgentGateway(
                chatPipeline,
                chatQueueLimiter,
                callbackFactory,
                traceRunner,
                taskManager
        );
    }

    @Test
    void shouldExecuteLegacyPipelineWithExplicitRunContext() {
        when(callbackFactory.createChatEventHandler(
                COMMAND.conversationId(),
                COMMAND.runId(),
                COMMAND.userId(),
                publisher
        )).thenReturn(callback);
        doAnswer(invocation -> {
            Runnable onAcquire = invocation.getArgument(4);
            onAcquire.run();
            return null;
        }).when(chatQueueLimiter).enqueue(
                eq(COMMAND.input()),
                eq(COMMAND.conversationId()),
                eq(COMMAND.userId()),
                same(publisher),
                any(Runnable.class)
        );
        doAnswer(invocation -> {
            Consumer<StreamCallback> businessLogic = invocation.getArgument(5);
            businessLogic.accept(callback);
            return null;
        }).when(traceRunner).run(
                eq(COMMAND.input()),
                eq(COMMAND.conversationId()),
                eq(COMMAND.runId()),
                eq(COMMAND.userId()),
                same(callback),
                any()
        );

        AgentRunHandle handle = gateway.start(COMMAND, publisher);

        assertEquals(COMMAND.runId(), handle.runId());
        assertEquals(COMMAND.conversationId(), handle.conversationId());
        ArgumentCaptor<StreamChatContext> contextCaptor = ArgumentCaptor.forClass(StreamChatContext.class);
        verify(chatPipeline).execute(contextCaptor.capture());
        StreamChatContext context = contextCaptor.getValue();
        assertEquals(COMMAND.input(), context.getQuestion());
        assertEquals(COMMAND.conversationId(), context.getConversationId());
        assertEquals(COMMAND.runId(), context.getTaskId());
        assertEquals(COMMAND.userId(), context.getUserId());
        assertTrue(context.isDeepThinking());
        assertEquals(callback, context.getCallback());
    }

    @Test
    void shouldCancelLegacyTaskThroughGateway() {
        gateway.cancel("run-1");

        verify(taskManager).cancel("run-1");
    }
}
