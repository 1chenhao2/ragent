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

package com.nageoffer.ai.ragent.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.admin.controller.vo.DashboardOverviewVO;
import com.nageoffer.ai.ragent.admin.service.DashboardService;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the existing dashboard overview through the Java business service.
 */
public class DashboardOverviewAgentTool implements AgentTool {

    private static final String TOOL_NAME = "dashboard.overview";
    private static final String DEFAULT_WINDOW = "7d";
    private static final Pattern WINDOW_PATTERN = Pattern.compile("^([1-9][0-9]*)([dh])$");
    private static final long MAX_DAYS = 90L;
    private static final long MAX_HOURS = MAX_DAYS * 24L;

    private final DashboardService dashboardService;
    private final ObjectMapper objectMapper;

    public DashboardOverviewAgentTool(DashboardService dashboardService, ObjectMapper objectMapper) {
        this.dashboardService = dashboardService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public Set<String> requiredScopes() {
        return Set.of("dashboard:read");
    }

    @Override
    public Map<String, Object> invoke(AgentToolPrincipal principal, Map<String, Object> arguments) {
        DashboardOverviewVO overview = dashboardService.loadOverview(resolveWindow(arguments));
        Map<String, Object> output = objectMapper.convertValue(overview, new TypeReference<>() {
        });
        output.values().removeIf(value -> value == null);
        return output;
    }

    private String resolveWindow(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("window");
        if (value == null) {
            return DEFAULT_WINDOW;
        }
        if (!(value instanceof String window)) {
            throw invalidWindow();
        }
        Matcher matcher = WINDOW_PATTERN.matcher(window.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw invalidWindow();
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ex) {
            throw invalidWindow();
        }
        String unit = matcher.group(2);
        if (("d".equals(unit) && amount > MAX_DAYS) || ("h".equals(unit) && amount > MAX_HOURS)) {
            throw invalidWindow();
        }
        return amount + unit;
    }

    private AgentToolException invalidWindow() {
        return new AgentToolException(
                400,
                "invalid_tool_arguments",
                "window must be between 1h and 720h or between 1d and 90d"
        );
    }
}
