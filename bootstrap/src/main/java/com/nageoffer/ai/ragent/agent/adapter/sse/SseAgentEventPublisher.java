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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent event adapter for the existing SSE protocol.
 */
public class SseAgentEventPublisher implements AgentEventPublisher {

    private final SseEmitterSender sender;
    private final SseEmitter emitter;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public SseAgentEventPublisher(SseEmitter emitter) {
        this(new SseEmitterSender(emitter), emitter);
    }

    SseAgentEventPublisher(SseEmitterSender sender) {
        this(sender, null);
    }

    private SseAgentEventPublisher(SseEmitterSender sender, SseEmitter emitter) {
        this.sender = sender;
        this.emitter = emitter;
    }

    @Override
    public void publish(AgentEvent event) {
        switch (event.type()) {
            case RUN_STARTED -> sender.sendEvent(SSEEventType.META.value(), (MetaPayload) event.data());
            case THINKING_DELTA, ANSWER_DELTA -> sender.sendEvent(
                    SSEEventType.MESSAGE.value(), (MessageDelta) event.data());
            case CANCELLED -> sender.sendEvent(SSEEventType.CANCEL.value(), (CompletionPayload) event.data());
            case REJECTED -> sender.sendEvent(SSEEventType.REJECT.value(), (MessageDelta) event.data());
            case COMPLETED -> sender.sendEvent(SSEEventType.FINISH.value(), (CompletionPayload) event.data());
        }
    }

    @Override
    public void complete() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        sender.complete();
    }

    @Override
    public void fail(Throwable throwable) {
        sender.fail(throwable);
    }

    @Override
    public void onClose(Runnable callback) {
        if (emitter == null) {
            return;
        }
        emitter.onCompletion(callback);
        emitter.onTimeout(callback);
        emitter.onError(throwable -> callback.run());
    }
}
