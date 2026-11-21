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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class RedisAgentToolIdempotencyStore implements AgentToolIdempotencyStore {

    private static final String KEY_PREFIX = "ragent:agent-tool:idempotency:";

    private final RedissonClient redissonClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final Duration lockWait;
    private final Duration lockLease;

    public RedisAgentToolIdempotencyStore(
            RedissonClient redissonClient,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AgentToolProperties properties
    ) {
        this.redissonClient = redissonClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = positive(properties.getIdempotencyTtl(), "idempotency-ttl");
        this.lockWait = positive(properties.getLockWait(), "lock-wait");
        this.lockLease = positive(properties.getLockLease(), "lock-lease");
    }

    @Override
    public AgentToolResult execute(
            AgentToolPrincipal principal,
            AgentToolCommand command,
            Supplier<AgentToolResult> invocation
    ) {
        String key = cacheKey(principal, command.idempotencyKey());
        RLock lock = redissonClient.getLock(key + ":lock");
        boolean acquired = false;
        try {
            acquired = lock.tryLock(lockWait.toMillis(), lockLease.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new AgentToolException(409, "idempotency_in_progress", "Agent Tool invocation is already in progress");
            }
            return executeLocked(key, command, invocation);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentToolException(503, "idempotency_store_unavailable", "Agent Tool invocation was interrupted");
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private AgentToolResult executeLocked(
            String key,
            AgentToolCommand command,
            Supplier<AgentToolResult> invocation
    ) {
        JsonNode payload = objectMapper.valueToTree(Map.of(
                "toolName", command.toolName(),
                "arguments", command.arguments()
        ));
        String cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            StoredInvocation stored = read(cached);
            if (!stored.payload().equals(payload)) {
                throw new AgentToolException(409, "idempotency_conflict", "Idempotency key was used with another payload");
            }
            return stored.result();
        }

        AgentToolResult result = invocation.get();
        redisTemplate.opsForValue().set(key, write(new StoredInvocation(payload, result)), ttl);
        return result;
    }

    private StoredInvocation read(String value) {
        try {
            return objectMapper.readValue(value, StoredInvocation.class);
        } catch (JsonProcessingException ex) {
            throw new AgentToolException(503, "idempotency_store_unavailable", "Stored Agent Tool result is invalid");
        }
    }

    private String write(StoredInvocation invocation) {
        try {
            return objectMapper.writeValueAsString(invocation);
        } catch (JsonProcessingException ex) {
            throw new AgentToolException(503, "idempotency_store_unavailable", "Agent Tool result cannot be stored");
        }
    }

    private String cacheKey(AgentToolPrincipal principal, String idempotencyKey) {
        String identity = String.join(
                "\u0000",
                Objects.toString(principal.tenantId(), ""),
                Objects.toString(principal.userId(), ""),
                Objects.toString(idempotencyKey, "")
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private Duration positive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("ragent.agent.tool." + property + " must be positive");
        }
        return value;
    }

    private record StoredInvocation(JsonNode payload, AgentToolResult result) {
    }
}
