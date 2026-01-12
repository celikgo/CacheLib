/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.cachelib.grpc.client;

import com.facebook.cachelib.grpc.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * CacheLibClient provides a simple Java client for the CacheLib gRPC server.
 *
 * <p>This client supports basic cache operations like Get, Set, Delete, and
 * advanced operations like MultiGet and Stats retrieval.
 *
 * <p>Example usage:
 * <pre>{@code
 * try (CacheLibClient client = new CacheLibClient("localhost", 50051)) {
 *     client.set("key", "value", 3600);  // TTL of 1 hour
 *     Optional<byte[]> value = client.get("key");
 *     client.delete("key");
 * }
 * }</pre>
 */
public class CacheLibClient implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(CacheLibClient.class);

    private final ManagedChannel channel;
    private final CacheServiceGrpc.CacheServiceBlockingStub blockingStub;
    private final CacheServiceGrpc.CacheServiceFutureStub futureStub;

    /**
     * Creates a new CacheLibClient connected to the specified host and port.
     *
     * @param host the server hostname
     * @param port the server port
     */
    public CacheLibClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()  // Use plaintext for simplicity; use TLS in production
                .build());
    }

    /**
     * Creates a new CacheLibClient using an existing channel.
     *
     * @param channel the gRPC channel to use
     */
    public CacheLibClient(ManagedChannel channel) {
        this.channel = channel;
        this.blockingStub = CacheServiceGrpc.newBlockingStub(channel);
        this.futureStub = CacheServiceGrpc.newFutureStub(channel);
        logger.info("CacheLibClient created");
    }

    /**
     * Gets a value from the cache.
     *
     * @param key the key to retrieve
     * @return Optional containing the value if found, empty otherwise
     */
    public Optional<byte[]> get(String key) {
        logger.debug("GET key={}", key);
        try {
            GetRequest request = GetRequest.newBuilder()
                    .setKey(key)
                    .build();
            GetResponse response = blockingStub.get(request);

            if (response.getFound()) {
                return Optional.of(response.getValue().toByteArray());
            }
            return Optional.empty();
        } catch (StatusRuntimeException e) {
            logger.error("GET failed for key={}: {}", key, e.getStatus());
            throw new CacheLibException("GET failed: " + e.getStatus(), e);
        }
    }

    /**
     * Gets a string value from the cache.
     *
     * @param key the key to retrieve
     * @return Optional containing the string value if found, empty otherwise
     */
    public Optional<String> getString(String key) {
        return get(key).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Gets a value with TTL information.
     *
     * @param key the key to retrieve
     * @return CacheEntry containing value and TTL info, or null if not found
     */
    public CacheEntry getWithTtl(String key) {
        logger.debug("GET with TTL key={}", key);
        try {
            GetRequest request = GetRequest.newBuilder()
                    .setKey(key)
                    .build();
            GetResponse response = blockingStub.get(request);

            if (response.getFound()) {
                return new CacheEntry(
                        response.getValue().toByteArray(),
                        response.getTtlRemaining()
                );
            }
            return null;
        } catch (StatusRuntimeException e) {
            logger.error("GET with TTL failed for key={}: {}", key, e.getStatus());
            throw new CacheLibException("GET failed: " + e.getStatus(), e);
        }
    }

    /**
     * Sets a value in the cache with no expiration.
     *
     * @param key   the key to set
     * @param value the value to store
     * @return true if successful
     */
    public boolean set(String key, byte[] value) {
        return set(key, value, 0);
    }

    /**
     * Sets a string value in the cache with no expiration.
     *
     * @param key   the key to set
     * @param value the string value to store
     * @return true if successful
     */
    public boolean set(String key, String value) {
        return set(key, value.getBytes(StandardCharsets.UTF_8), 0);
    }

    /**
     * Sets a string value in the cache with TTL.
     *
     * @param key        the key to set
     * @param value      the string value to store
     * @param ttlSeconds time-to-live in seconds (0 = no expiration)
     * @return true if successful
     */
    public boolean set(String key, String value, long ttlSeconds) {
        return set(key, value.getBytes(StandardCharsets.UTF_8), ttlSeconds);
    }

    /**
     * Sets a value in the cache with TTL.
     *
     * @param key        the key to set
     * @param value      the value to store
     * @param ttlSeconds time-to-live in seconds (0 = no expiration)
     * @return true if successful
     */
    public boolean set(String key, byte[] value, long ttlSeconds) {
        logger.debug("SET key={} size={} ttl={}", key, value.length, ttlSeconds);
        try {
            SetRequest request = SetRequest.newBuilder()
                    .setKey(key)
                    .setValue(ByteString.copyFrom(value))
                    .setTtlSeconds(ttlSeconds)
                    .build();
            SetResponse response = blockingStub.set(request);

            if (!response.getSuccess()) {
                logger.warn("SET failed for key={}: {}", key, response.getMessage());
            }
            return response.getSuccess();
        } catch (StatusRuntimeException e) {
            logger.error("SET failed for key={}: {}", key, e.getStatus());
            throw new CacheLibException("SET failed: " + e.getStatus(), e);
        }
    }

    /**
     * Deletes a key from the cache.
     *
     * @param key the key to delete
     * @return true if the key existed and was deleted
     */
    public boolean delete(String key) {
        logger.debug("DELETE key={}", key);
        try {
            DeleteRequest request = DeleteRequest.newBuilder()
                    .setKey(key)
                    .build();
            DeleteResponse response = blockingStub.delete(request);
            return response.getKeyExisted();
        } catch (StatusRuntimeException e) {
            logger.error("DELETE failed for key={}: {}", key, e.getStatus());
            throw new CacheLibException("DELETE failed: " + e.getStatus(), e);
        }
    }

    /**
     * Checks if a key exists in the cache.
     *
     * @param key the key to check
     * @return true if the key exists
     */
    public boolean exists(String key) {
        logger.debug("EXISTS key={}", key);
        try {
            ExistsRequest request = ExistsRequest.newBuilder()
                    .setKey(key)
                    .build();
            ExistsResponse response = blockingStub.exists(request);
            return response.getExists();
        } catch (StatusRuntimeException e) {
            logger.error("EXISTS failed for key={}: {}", key, e.getStatus());
            throw new CacheLibException("EXISTS failed: " + e.getStatus(), e);
        }
    }

    /**
     * Gets multiple values from the cache in a single call.
     *
     * @param keys the keys to retrieve
     * @return map of key to optional value
     */
    public Map<String, Optional<byte[]>> multiGet(List<String> keys) {
        logger.debug("MULTIGET keys={}", keys);
        try {
            MultiGetRequest request = MultiGetRequest.newBuilder()
                    .addAllKeys(keys)
                    .build();
            MultiGetResponse response = blockingStub.multiGet(request);

            return response.getResultsList().stream()
                    .collect(Collectors.toMap(
                            KeyValue::getKey,
                            kv -> kv.getFound()
                                    ? Optional.of(kv.getValue().toByteArray())
                                    : Optional.empty()
                    ));
        } catch (StatusRuntimeException e) {
            logger.error("MULTIGET failed: {}", e.getStatus());
            throw new CacheLibException("MULTIGET failed: " + e.getStatus(), e);
        }
    }

    /**
     * Sets multiple values in the cache in a single call.
     *
     * @param entries map of key to value
     * @return number of successfully set entries
     */
    public int multiSet(Map<String, byte[]> entries) {
        return multiSet(entries, 0);
    }

    /**
     * Sets multiple values in the cache with TTL.
     *
     * @param entries    map of key to value
     * @param ttlSeconds time-to-live in seconds for all entries
     * @return number of successfully set entries
     */
    public int multiSet(Map<String, byte[]> entries, long ttlSeconds) {
        logger.debug("MULTISET count={} ttl={}", entries.size(), ttlSeconds);
        try {
            MultiSetRequest.Builder requestBuilder = MultiSetRequest.newBuilder();

            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                requestBuilder.addItems(SetRequest.newBuilder()
                        .setKey(entry.getKey())
                        .setValue(ByteString.copyFrom(entry.getValue()))
                        .setTtlSeconds(ttlSeconds)
                        .build());
            }

            MultiSetResponse response = blockingStub.multiSet(requestBuilder.build());

            if (!response.getSuccess()) {
                logger.warn("MULTISET partially failed: {}", response.getMessage());
            }
            return response.getSucceededCount();
        } catch (StatusRuntimeException e) {
            logger.error("MULTISET failed: {}", e.getStatus());
            throw new CacheLibException("MULTISET failed: " + e.getStatus(), e);
        }
    }

    /**
     * Gets cache statistics.
     *
     * @return CacheStats object with current statistics
     */
    public CacheStats getStats() {
        logger.debug("STATS");
        try {
            StatsRequest request = StatsRequest.newBuilder()
                    .setDetailed(true)
                    .build();
            StatsResponse response = blockingStub.stats(request);

            return new CacheStats(
                    response.getTotalSize(),
                    response.getUsedSize(),
                    response.getItemCount(),
                    response.getHitRate(),
                    response.getGetCount(),
                    response.getHitCount(),
                    response.getMissCount(),
                    response.getSetCount(),
                    response.getEvictionCount(),
                    response.getNvmEnabled(),
                    response.getNvmSize(),
                    response.getNvmUsed(),
                    response.getUptimeSeconds()
            );
        } catch (StatusRuntimeException e) {
            logger.error("STATS failed: {}", e.getStatus());
            throw new CacheLibException("STATS failed: " + e.getStatus(), e);
        }
    }

    /**
     * Pings the server to check connectivity.
     *
     * @return true if the server responds
     */
    public boolean ping() {
        logger.debug("PING");
        try {
            PingRequest request = PingRequest.newBuilder().build();
            PingResponse response = blockingStub.ping(request);
            logger.debug("PONG: {} at {}", response.getMessage(), response.getTimestamp());
            return true;
        } catch (StatusRuntimeException e) {
            logger.error("PING failed: {}", e.getStatus());
            return false;
        }
    }

    @Override
    public void close() {
        logger.info("Closing CacheLibClient");
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warn("Interrupted while closing channel", e);
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Represents a cache entry with value and TTL information.
     */
    public static class CacheEntry {
        private final byte[] value;
        private final long ttlRemaining;

        public CacheEntry(byte[] value, long ttlRemaining) {
            this.value = value;
            this.ttlRemaining = ttlRemaining;
        }

        public byte[] getValue() {
            return value;
        }

        public String getValueAsString() {
            return new String(value, StandardCharsets.UTF_8);
        }

        public long getTtlRemaining() {
            return ttlRemaining;
        }

        public boolean hasExpiry() {
            return ttlRemaining > 0;
        }
    }

    /**
     * Represents cache statistics.
     */
    public static class CacheStats {
        private final long totalSize;
        private final long usedSize;
        private final long itemCount;
        private final double hitRate;
        private final long getCount;
        private final long hitCount;
        private final long missCount;
        private final long setCount;
        private final long evictionCount;
        private final boolean nvmEnabled;
        private final long nvmSize;
        private final long nvmUsed;
        private final long uptimeSeconds;

        public CacheStats(long totalSize, long usedSize, long itemCount,
                         double hitRate, long getCount, long hitCount,
                         long missCount, long setCount, long evictionCount,
                         boolean nvmEnabled, long nvmSize, long nvmUsed,
                         long uptimeSeconds) {
            this.totalSize = totalSize;
            this.usedSize = usedSize;
            this.itemCount = itemCount;
            this.hitRate = hitRate;
            this.getCount = getCount;
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.setCount = setCount;
            this.evictionCount = evictionCount;
            this.nvmEnabled = nvmEnabled;
            this.nvmSize = nvmSize;
            this.nvmUsed = nvmUsed;
            this.uptimeSeconds = uptimeSeconds;
        }

        // Getters
        public long getTotalSize() { return totalSize; }
        public long getUsedSize() { return usedSize; }
        public long getItemCount() { return itemCount; }
        public double getHitRate() { return hitRate; }
        public long getGetCount() { return getCount; }
        public long getHitCount() { return hitCount; }
        public long getMissCount() { return missCount; }
        public long getSetCount() { return setCount; }
        public long getEvictionCount() { return evictionCount; }
        public boolean isNvmEnabled() { return nvmEnabled; }
        public long getNvmSize() { return nvmSize; }
        public long getNvmUsed() { return nvmUsed; }
        public long getUptimeSeconds() { return uptimeSeconds; }

        @Override
        public String toString() {
            return String.format(
                    "CacheStats{totalSize=%d, usedSize=%d, itemCount=%d, " +
                    "hitRate=%.2f%%, gets=%d, hits=%d, misses=%d, sets=%d, " +
                    "evictions=%d, nvmEnabled=%s, uptime=%ds}",
                    totalSize, usedSize, itemCount, hitRate * 100,
                    getCount, hitCount, missCount, setCount,
                    evictionCount, nvmEnabled, uptimeSeconds
            );
        }
    }
}
