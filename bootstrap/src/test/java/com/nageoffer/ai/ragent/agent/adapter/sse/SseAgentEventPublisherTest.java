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

package com.nageoffer.ai.ragent.agent.adapter.sse;

import com.nageoffer.ai.ragent.agent.event.AgentEvent;
import com.nageoffer.ai.ragent.agent.event.AgentEventPublisher;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class SseAgentEventPublisherTest {

    @Mock
    private SseEmitterSender sender;

    @Test
    void shouldMapRunStartedToExistingMetaEvent() {
        AgentEventPublisher publisher = new SseAgentEventPublisher(sender);

        publisher.publish(AgentEvent.runStarted("conversation-1", "run-1"));

        org.mockito.Mockito.verify(sender).sendEvent(
                SSEEventType.META.value(),
                new MetaPayload("conversation-1", "run-1")
        );
    }

    @Test
    void shouldMapThinkingDeltaToExistingMessageEvent() {
        AgentEventPublisher publisher = new SseAgentEventPublisher(sender);

        publisher.publish(AgentEvent.thinkingDelta("thinking"));

        org.mockito.Mockito.verify(sender).sendEvent(
                SSEEventType.MESSAGE.value(),
                new MessageDelta("think", "thinking")
        );
    }

    @Test
    void shouldMapCancelledToExistingCancelEvent() {
        AgentEventPublisher publisher = new SseAgentEventPublisher(sender);
        CompletionPayload completion = new CompletionPayload("message-1", "Cancelled");

        publisher.publish(AgentEvent.cancelled(completion));
        publisher.complete();

        InOrder inOrder = inOrder(sender);
        inOrder.verify(sender).sendEvent(SSEEventType.CANCEL.value(), completion);
        inOrder.verify(sender).sendEvent(SSEEventType.DONE.value(), "[DONE]");
        inOrder.verify(sender).complete();
    }

    @Test
    void shouldMapAnswerDeltaAndCompletionToExistingSseEvents() {
        AgentEventPublisher publisher = new SseAgentEventPublisher(sender);
        MessageDelta delta = new MessageDelta("response", "答案片段");
        CompletionPayload completion = new CompletionPayload("message-1", "测试会话");

        publisher.publish(AgentEvent.answerDelta(delta));
        publisher.publish(AgentEvent.completed(completion));
        publisher.complete();

        InOrder inOrder = inOrder(sender);
        inOrder.verify(sender).sendEvent(SSEEventType.MESSAGE.value(), delta);
        inOrder.verify(sender).sendEvent(SSEEventType.FINISH.value(), completion);
        inOrder.verify(sender).sendEvent(SSEEventType.DONE.value(), "[DONE]");
        inOrder.verify(sender).complete();
    }
}
