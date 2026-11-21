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

package com.nageoffer.ai.ragent.knowledge.dao.entity;

/**
 * One row in the bounded {@code knowledge_document.overview} aggregate, grouped by
 * {@code (kb_id, status, enabled)}. Counts and totals are inclusive of soft-deleted rows
 * the Java aggregate chooses to include; the Agent Tool filters them out before returning.
 *
 * @param kbId           knowledge base identifier
 * @param kbName         knowledge base display name
 * @param status         document processing status; {@code null} means disabled or unknown
 * @param enabled        whether the knowledge base / document is currently enabled
 * @param documentCount  number of documents in this group
 * @param chunkTotal     total chunk count across the group
 * @param byteTotal      total bytes across the group
 */
public record KnowledgeDocumentOverviewRow(
        String kbId,
        String kbName,
        String status,
        Integer enabled,
        long documentCount,
        long chunkTotal,
        long byteTotal
) {
}