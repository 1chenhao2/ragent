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

import com.nageoffer.ai.ragent.agent.gateway.AgentAccessContext;
import com.nageoffer.ai.ragent.agent.gateway.AgentRunCommand;
import com.nageoffer.ai.ragent.agent.gateway.AgentType;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentConversationRecorderTest {

    @Test
    void shouldPersistUserAndAssistantMessagesInJavaConversationMemory() {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        ConversationGroupService conversationGroupService = mock(ConversationGroupService.class);
        when(memoryService.append("conversation-1", "user-1", ChatMessage.user("question")))
                .thenReturn("question-message-1");
        when(memoryService.append(
                org.mockito.ArgumentMatchers.eq("conversation-1"),
                org.mockito.ArgumentMatchers.eq("user-1"),
                org.mockito.ArgumentMatchers.argThat(message -> message.getRole() == ChatMessage.Role.ASSISTANT)
        )).thenReturn("answer-message-1");
        when(conversationGroupService.findConversation("conversation-1", "user-1"))
                .thenReturn(null, ConversationDO.builder().title("Conversation title").build());
        AgentConversationRecorder recorder = new JavaAgentConversationRecorder(
                memoryService,
                conversationGroupService
        );
        AgentRunCommand command = command();

        AgentConversationSession session = recorder.begin(command);
        SourceRef source = SourceRef.builder().index(1).docId("document-1").build();
        CompletionPayload completion = recorder.complete(
                session,
                "answer",
                "thinking",
                List.of(source),
                ChatMessage.MessageStatus.NORMAL
        );

        ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(memoryService, times(2)).append(
                org.mockito.ArgumentMatchers.eq("conversation-1"),
                org.mockito.ArgumentMatchers.eq("user-1"),
                messageCaptor.capture()
        );
        ChatMessage assistant = messageCaptor.getAllValues().getLast();
        assertEquals("answer", assistant.getContent());
        assertEquals("thinking", assistant.getThinkingContent());
        assertEquals("question-message-1", assistant.getReplyToMessageId());
        assertEquals(List.of(source), assistant.getSources());
        assertEquals(ChatMessage.MessageStatus.NORMAL, assistant.getMessageStatus());
        assertEquals("answer-message-1", completion.messageId());
        assertEquals("Conversation title", completion.title());
    }

    private AgentRunCommand command() {
        return new AgentRunCommand(
                "request-1",
                "run-1",
                "conversation-1",
                "user-1",
                AgentType.KNOWLEDGE,
                "question",
                false,
                AgentAccessContext.denied()
        );
    }
}
