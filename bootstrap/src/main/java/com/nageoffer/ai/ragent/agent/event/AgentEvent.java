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

package com.nageoffer.ai.ragent.agent.event;

import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;

/**
 * Agent 运行事件
 */
public record AgentEvent(AgentEventType type, Object data) {
    /**
     * Creates a run started event.
     */
    public static AgentEvent runStarted(String conversationId, String runId) {
        return new AgentEvent(AgentEventType.RUN_STARTED, new MetaPayload(conversationId, runId));
    }

    /**
     * Creates a thinking delta event.
     */
    public static AgentEvent thinkingDelta(String delta) {
        return new AgentEvent(AgentEventType.THINKING_DELTA, new MessageDelta("think", delta));
    }
    /**
     * 创建回答增量事件
     */
    public static AgentEvent answerDelta(MessageDelta delta) {
        return new AgentEvent(AgentEventType.ANSWER_DELTA, delta);
    }

    /**
     * Creates an approval-required event.
     */
    public static AgentEvent approvalRequired(String approvalId) {
        return new AgentEvent(AgentEventType.APPROVAL_REQUIRED, new ApprovalPayload(approvalId));
    }

    /**
     * Creates a cancelled event.
     */
    public static AgentEvent cancelled(CompletionPayload payload) {
        return new AgentEvent(AgentEventType.CANCELLED, payload);
    }

    /**
     * Creates a rejected event.
     */
    public static AgentEvent rejected(String message) {
        return new AgentEvent(AgentEventType.REJECTED, new MessageDelta("response", message));
    }

    /**
     * 创建完成事件
     */
    public static AgentEvent completed(CompletionPayload payload) {
        return new AgentEvent(AgentEventType.COMPLETED, payload);
    }
}
