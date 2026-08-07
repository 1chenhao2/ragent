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

package com.nageoffer.ai.ragent.rag.service.handler;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.event.AgentEvent;
import com.nageoffer.ai.ragent.agent.event.AgentEventPublisher;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Legacy RAG callback adapted to Agent events.
 */
@Slf4j
public class AgentEventStreamChatHandler implements StreamCallback {

    private static final String TYPE_THINK = "think";
    private static final String TYPE_RESPONSE = "response";

    private final int messageChunkSize;
    private final AgentEventPublisher publisher;
    private final String conversationId;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager;
    private final boolean sendTitleOnComplete;
    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private long thinkingStartMs;
    private int thinkingDurationSeconds;
    private List<SourceRef> sources;
    private List<GroundingChunk> groundingChunks;
    private String replyToMessageId;

    public AgentEventStreamChatHandler(AIModelProperties modelProperties,
                                       ConversationMemoryService memoryService,
                                       ConversationGroupService conversationGroupService,
                                       StreamTaskManager taskManager,
                                       String conversationId,
                                       String taskId,
                                       String userId,
                                       AgentEventPublisher publisher) {
        this.publisher = publisher;
        this.conversationId = conversationId;
        this.taskId = taskId;
        this.memoryService = memoryService;
        this.conversationGroupService = conversationGroupService;
        this.taskManager = taskManager;
        this.userId = userId;
        this.messageChunkSize = resolveMessageChunkSize(modelProperties);
        this.sendTitleOnComplete = shouldSendTitle();
        initialize();
    }

    private void initialize() {
        publisher.publish(AgentEvent.runStarted(conversationId, taskId));
        taskManager.register(taskId, publisher, this::buildCompletionPayloadOnCancel);
    }

    private int resolveMessageChunkSize(AIModelProperties modelProperties) {
        return Math.max(1, Optional.ofNullable(modelProperties.getStream())
                .map(AIModelProperties.Stream::getMessageChunkSize)
                .orElse(5));
    }

    private boolean shouldSendTitle() {
        ConversationDO existingConversation = conversationGroupService.findConversation(conversationId, userId);
        return existingConversation == null || StrUtil.isBlank(existingConversation.getTitle());
    }

    private CompletionPayload buildCompletionPayloadOnCancel() {
        String content = answer.toString();
        String messageId = null;
        if (StrUtil.isNotBlank(content)) {
            try {
                String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
                ChatMessage message = ChatMessage.assistant(content, thinkingContent, resolveThinkingDuration());
                message.setSources(sources);
                message.setRetrievedChunks(groundingChunks);
                message.setReplyToMessageId(replyToMessageId);
                message.setMessageStatus(ChatMessage.MessageStatus.INTERRUPTED);
                messageId = memoryService.append(conversationId, userId, message);
            } catch (Exception ex) {
                log.error("取消时持久化消息失败，conversationId：{}", conversationId, ex);
            }
        }
        String messageIdText = StrUtil.isBlank(messageId) ? null : messageId;
        return new CompletionPayload(messageIdText, resolveTitleForEvent(), sources,
                ChatMessage.MessageStatus.INTERRUPTED);
    }

    @Override
    public void onReplyToMessageId(String messageId) {
        this.replyToMessageId = messageId;
    }

    @Override
    public void onSources(List<SourceRef> sources) {
        if (taskManager.isCancelled(taskId) || CollUtil.isEmpty(sources)) {
            return;
        }
        this.sources = sources;
    }

    @Override
    public void onGroundingChunks(List<GroundingChunk> chunks) {
        if (taskManager.isCancelled(taskId) || CollUtil.isEmpty(chunks)) {
            return;
        }
        this.groundingChunks = chunks;
    }

    @Override
    public void onContent(String chunk) {
        if (taskManager.isCancelled(taskId) || StrUtil.isBlank(chunk)) {
            return;
        }
        if (thinkingStartMs > 0 && thinkingDurationSeconds == 0) {
            thinkingDurationSeconds = Math.max(1,
                    Math.round((System.currentTimeMillis() - thinkingStartMs) / 1000.0f));
        }
        answer.append(chunk);
        sendChunked(TYPE_RESPONSE, chunk);
    }

    @Override
    public void onThinking(String chunk) {
        if (taskManager.isCancelled(taskId) || StrUtil.isBlank(chunk)) {
            return;
        }
        if (thinkingStartMs == 0) {
            thinkingStartMs = System.currentTimeMillis();
        }
        thinking.append(chunk);
        sendChunked(TYPE_THINK, chunk);
    }

    @Override
    public void onComplete() {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        String messageId = null;
        try {
            String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
            ChatMessage message = ChatMessage.assistant(answer.toString(), thinkingContent, resolveThinkingDuration());
            message.setSources(sources);
            message.setRetrievedChunks(groundingChunks);
            message.setReplyToMessageId(replyToMessageId);
            message.setMessageStatus(ChatMessage.MessageStatus.NORMAL);
            messageId = memoryService.append(conversationId, userId, message);
        } catch (Exception ex) {
            log.error("对话完成时持久化消息失败，conversationId：{}", conversationId, ex);
        }
        String messageIdText = StrUtil.isBlank(messageId) ? null : messageId;
        publisher.publish(AgentEvent.completed(new CompletionPayload(messageIdText, resolveTitleForEvent(), sources,
                ChatMessage.MessageStatus.NORMAL)));
        taskManager.unregister(taskId);
        publisher.complete();
    }

    @Override
    public void onError(Throwable throwable) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        taskManager.unregister(taskId);
        publisher.fail(throwable);
    }

    private void sendChunked(String type, String content) {
        int length = content.length();
        int index = 0;
        int count = 0;
        StringBuilder buffer = new StringBuilder();
        while (index < length) {
            int codePoint = content.codePointAt(index);
            buffer.appendCodePoint(codePoint);
            index += Character.charCount(codePoint);
            count++;
            if (count >= messageChunkSize) {
                publishDelta(type, buffer.toString());
                buffer.setLength(0);
                count = 0;
            }
        }
        if (!buffer.isEmpty()) {
            publishDelta(type, buffer.toString());
        }
    }

    private void publishDelta(String type, String delta) {
        if (TYPE_THINK.equals(type)) {
            publisher.publish(AgentEvent.thinkingDelta(delta));
            return;
        }
        publisher.publish(AgentEvent.answerDelta(new MessageDelta(TYPE_RESPONSE, delta)));
    }

    private Integer resolveThinkingDuration() {
        return thinkingDurationSeconds > 0 ? thinkingDurationSeconds : null;
    }

    private String resolveTitleForEvent() {
        if (!sendTitleOnComplete) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        return "新对话";
    }
}
