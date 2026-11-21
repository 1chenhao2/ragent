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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.agent.service.DashboardAnalysisService;
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Administrator-only Agent operations.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/agent")
public class AdminAgentController {

    private final DashboardAnalysisService dashboardAnalysisService;
    private final RAGDefaultProperties ragDefaultProperties;

    @IdempotentSubmit(
            key = "T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()",
            message = "An Agent analysis is already running for the current user"
    )
    @GetMapping(value = "/dashboard-analysis", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter dashboardAnalysis(
            @RequestParam String question,
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false, defaultValue = "false") Boolean deepThinking
    ) {
        SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());
        dashboardAnalysisService.streamAnalysis(question, conversationId, Boolean.TRUE.equals(deepThinking), emitter);
        return emitter;
    }
}
