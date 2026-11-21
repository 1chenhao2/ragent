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

package com.nageoffer.ai.ragent.agent.conversation;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps conversation persistence in the Java business system while execution runs in Python.
 */
@Component
@RequiredArgsConstructor
public class JavaAgentConversationRecorder implements AgentConversationRecorder {

    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;

    @Override
    public AgentConversationSession begin(AgentRunCommand command) {
        ConversationDO existing = conversationGroupService.findConversation(
                command.conversationId(),
                command.userId()
        );
        String questionMessageId = memoryService.append(
                command.conversationId(),
                command.userId(),
                ChatMessage.user(command.input())
        );
        return new AgentConversationSession(
                command.conversationId(),
                command.userId(),
                questionMessageId,
                existing == null || StrUtil.isBlank(existing.getTitle())
        );
    }

    @Override
    public CompletionPayload complete(
            AgentConversationSession session,
            String answer,
            String thinking,
            List<SourceRef> sources,
            ChatMessage.MessageStatus status
    ) {
        String messageId = null;
        if (StrUtil.isNotBlank(answer)) {
            ChatMessage message = ChatMessage.assistant(answer, StrUtil.blankToDefault(thinking, null));
            message.setReplyToMessageId(session.replyToMessageId());
            message.setSources(sources);
            message.setMessageStatus(status);
            messageId = memoryService.append(session.conversationId(), session.userId(), message);
        }
        return new CompletionPayload(
                StrUtil.blankToDefault(messageId, null),
                resolveTitle(session),
                sources,
                status
        );
    }

    private String resolveTitle(AgentConversationSession session) {
        if (!session.titleRequested()) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(
                session.conversationId(),
                session.userId()
        );
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        return "\u65b0\u5bf9\u8bdd";
    }
}
