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

package com.nageoffer.ai.ragent.knowledge.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentOverviewRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocumentDO> {

    /**
     * Aggregate per-(knowledge base, status, enabled) row counts, chunk totals, and byte totals
     * for the provided knowledge base ids. Soft-deleted documents are excluded.
     *
     * <p>Used by the read-only {@code knowledge_document.overview} Agent Tool.
     */
    @Select({
            "<script>",
            "SELECT d.kb_id AS kb_id,",
            "       COALESCE(MIN(k.name), '') AS kb_name,",
            "       d.status AS status,",
            "       k.enabled AS enabled,",
            "       COUNT(1) AS document_count,",
            "       COALESCE(SUM(d.chunk_count), 0) AS chunk_total,",
            "       COALESCE(SUM(d.file_size), 0) AS byte_total",
            "  FROM t_knowledge_document d",
            "  JOIN t_knowledge_base k ON k.id = d.kb_id",
            " WHERE d.deleted = 0",
            "   AND k.deleted = 0",
            "   AND d.kb_id IN",
            "   <foreach collection='kbIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            " GROUP BY d.kb_id, d.status, k.enabled",
            " ORDER BY d.kb_id ASC, d.status ASC",
            "</script>"
    })
    List<KnowledgeDocumentOverviewRow> aggregateOverview(@Param("kbIds") Collection<String> kbIds);
}