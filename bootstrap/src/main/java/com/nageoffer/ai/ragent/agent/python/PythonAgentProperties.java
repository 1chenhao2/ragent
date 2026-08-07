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

package com.nageoffer.ai.ragent.agent.python;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Python Agent service connection and delegated identity settings.
 */
@Data
@Component
@ConfigurationProperties(prefix = "ragent.agent.python")
public class PythonAgentProperties {

    private String baseUrl = "http://127.0.0.1:8000";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private Duration streamReadTimeout = Duration.ofSeconds(90);
    private DelegatedToken delegatedToken = new DelegatedToken();

    @Data
    public static class DelegatedToken {

        private String secret = "";
        private String issuer = "ragent-java";
        private String audience = "ragent-agent-service";
        private Duration ttl = Duration.ofMinutes(5);
    }
}
