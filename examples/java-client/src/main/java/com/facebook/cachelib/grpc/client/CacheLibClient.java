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
 * CacheLibClient provides a comprehensive Java client for the CacheLib gRPC server.
 *
 * <p>This client supports:
 * <ul>
 *   <li>Basic operations: Get, Set, Delete, Exists</li>
 *   <li>Batch operations: MultiGet, MultiSet</li>
 *   <li>Atomic operations: SetNX (distributed locks), Increment, Decrement, CompareAndSwap</li>
 *   <li>TTL operations: GetTTL, Touch</li>
 *   <li>Administration: Stats, Ping, Scan, Flush</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * try (CacheLibClient client = new CacheLibClient("localhost", 50051)) {
 *     // Basic operations
 *     client.set("key", "value", 3600);  // TTL of 1 hour
 *     Optional<byte[]> value = client.get("key");
 *
 *     // Distributed locking with SetNX
 *     SetNXResult lock = client.setNX("lock:resource", "owner-123", 30);
 *     if (lock.wasSet()) {
 *         // Acquired lock
 *     }
 *
 *     // Rate limiting with Increment
 *     IncrementResult count = client.increment("rate:user:123", 1, 60);
 *     if (count.getNewValue() > 100) {
 *         // Rate limit exceeded
 *     }
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

    // =========================================================================
    // Basic Operations
    // =========================================================================

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

    // =========================================================================
    // Batch Operations
    // =========================================================================

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

    // =========================================================================
    // Atomic Operations
    // =========================================================================

    /**
     * Sets a value only if the key doesn't exist (Set if Not eXists).
     * Useful for distributed locks and idempotent operations.
     *
     * <p>Example - Distributed Lock:
     * <pre>{@code
     * SetNXResult result = client.setNX("lock:resource", "owner-123", 30);
     * if (result.wasSet()) {
     *     try {
     *         // Do work while holding the lock
     *     } finally {
     *         client.delete("lock:resource");
     *     }
     * } else {
     *     // Lock is held by another owner
     * }
     * }</pre>
     *
     * @param key        the key to set
     * @param value      the value to store
     * @param ttlSeconds time-to-live in seconds (0 = no expiration)
     * @return SetNXResult indicating if the key was set
     */
    public SetNXResult setNX(String key, byte[] value, long ttlSeconds) {
        logger.debug("SETNX key={} ttl={}", key, ttlSeconds);
        try {
            SetNXRequest request = SetNXRequest.newBuilder()
                    .setKey(key)
                    .setValue(ByteString.copyFrom(value))
                    .setTtlSeconds(ttlSeconds)
                    .build();
            SetNXResponse response = blockingStub.setNX(request);

            return new SetNXResult(
                    response.getWasSet(),
                    response.getExistingValue().toByteArray(),
                    response.getMessage()
            );
        } catch (StatusRuntimeException e) {
            logger.error("SETNX failed for key={}: {}", key, e.getStatus());
            throw new CacheLibException("SETNX failed: " + e.getStatus(), e);
        }
    }

    /**
     * Sets a string value only if the key doesn't exist.
     *
     * @param key        the key to set
     * @param value      the string value to store
     * @param ttlSeconds time-to-live in seconds
     * @return SetNXResult indicating if the key was set
     */
    public SetNXResult setNX(String key, String value, long ttlSeconds) {
        return setNX(key, value.getBytes(StandardCharsets.UTF_8), ttlSeconds);
    }

    /**
     * Atomically increments a numeric value stored at key.
     * If the key doesn't exist, it's created with value = delta.
     *
     * <p>Example - Rate Limiting:
     * <pre>{@code
     * String rateLimitKey = "rate:user:" + userId;
     * IncrementResult result = client.increment(rateLimitKey, 1, 60); // 60 second window
     * if (result.getNewValue() > 100) {
     *     throw new RateLimitExceededException();
     * }
     * }</pre>
     *
     * @param key        the key to increment
     * @param delta      the amount to add (default: 1)
     * @param ttlSeconds time-to-live in seconds for new keys
     * @return IncrementResult with the new value
     */
    public IncrementResult increment(String key, long delta, long ttlSeconds) {
        logger.debug("INCR key={} delta={} ttl={}", key, delta, ttlSeconds);
        try {
            IncrementRequest request = IncrementRequest.newBuilder()
                    .setKey(key)
                    .setDelta(delta)
                    .setTtlSeconds(ttlSeconds)
                    .build();
            IncrementResponse response = blockingStub.increment(request);

            return new IncrementResult(
                    response.getSuccess(),
                    response.getNewValue(),
                    response.getMessage()
            );
        } catch (StatusRuntimeException e) {
            logger.error("INCR failed for key={}: {}", key, e.getStatus());
            throw new CacheLibException("INCR failed: " + e.getStatus(), e);
        }
    }

    /**
     * Increments by 1 with no TTL.
     *
     * @param key the key to increment
     * @return IncrementResult with the new value
     */
    public IncrementResult increment(String key) {
        return increment(key, 1, 0);
    }

    /**
     * Atomically decrements a numeric value stored at key.
     * If the key doesn't exist, it's created with value = -delta.
     *
     * @param key        the key to decrement
     * @param delta      the amount to subtract (default: 1)
     * @param ttlSeconds time-to-live in seconds for new keys
     * @return IncrementResult with the new value
     */
    public IncrementResult decrement(String key, long delta, long ttlSeconds) {
        logger.debug("DECR key={} delta={} ttl={}", key, delta, ttlSeconds);
        try {
            DecrementRequest request = DecrementRequest.newBuilder()
                    .setKey(key)
                    .setDelta(delta)
                    .setTtlSeconds(ttlSeconds)
                    .build();
            DecrementResponse response = blockingStub.decrement(request);

            return new IncrementResult(
                    response.getSuccess(),
                    response.getNewValue(),
                    response.getMessage()
            );
        } catch (StatusRuntimeException e) {
            logger.error("DECR failed for key={}: {}", key, e.getStatus());
            throw new CacheLibException("DECR failed: " + e.getStatus(), e);
        }
    }

    /**
     * Decrements by 1 with no TTL.
     *
     * @param key the key to decrement
     * @return IncrementResult with the new value
     */
    public IncrementResult decrement(String key) {
        return decrement(key, 1, 0);
    }

    /**
     * Atomically updates a value if it matches the expected value.
     * Useful for optimistic locking patterns.
     *
     * <p>Example - Optimistic Locking:
     * <pre>{@code
     * CacheEntry entry = client.getWithTtl("version");
     * String currentVersion = entry.getValueAsString();
     * String newVersion = computeNewVersion(currentVersion);
     *
     * CompareAndSwapResult result = client.compareAndSwap(
     *     "version", currentVersion, newVersion, 0);
     * if (!result.wasSwapped()) {
     *     // Another client updated the value, retry
     * }
     * }</pre>
     *
     * @param key           the key to update
     * @param expectedValue the expected current value
     * @param newValue      the new value to set
     * @param ttlSeconds    TTL (0 = keep existing, -1 = no expiration)
     * @return CompareAndSwapResult indicating if the swap succeeded
     */
    public CompareAndSwapResult compareAndSwap(String key, byte[] expectedValue,
                                                byte[] newValue, long ttlSeconds) {
        logger.debug("CAS key={}", key);
        try {
            CompareAndSwapRequest request = CompareAndSwapRequest.newBuilder()
                    .setKey(key)
                    .setExpectedValue(ByteString.copyFrom(expectedValue))
                    .setNewValue(ByteString.copyFrom(newValue))
                    .setTtlSeconds(ttlSeconds)
                    .build();
            CompareAndSwapResponse response = blockingStub.compareAndSwap(request);

            return new CompareAndSwapResult(
                    response.getSuccess(),
                    response.getActualValue().toByteArray(),
                    response.getMessage()
            );
        } catch (StatusRuntimeException e) {
            logger.error("CAS failed for key={}: {}", key, e.getStatus());
            throw new CacheLibException("CAS failed: " + e.getStatus(), e);
        }
    }

    /**
     * Compare and swap with string values.
     */
    public CompareAndSwapResult compareAndSwap(String key, String expectedValue,
                                                String newValue, long ttlSeconds) {
        return compareAndSwap(
                key,
                expectedValue.getBytes(StandardCharsets.UTF_8),
                newValue.getBytes(StandardCharsets.UTF_8),
                ttlSeconds
        );
    }

    // =========================================================================
    // TTL Operations
    // =========================================================================

    /**
     * Gets the remaining TTL for a key without retrieving the value.
     * Useful for monitoring cache freshness and implementing cache warming.
     *
     * @param key the key to check
     * @return TTL in seconds: -2 = not found, -1 = no expiration, 0+ = seconds remaining
     */
    public long getTTL(String key) {
        logger.debug("TTL key={}", key);
        try {
            GetTTLRequest request = GetTTLRequest.newBuilder()
                    .setKey(key)
                    .build();
            GetTTLResponse response = blockingStub.getTTL(request);
            return response.getTtlSeconds();
        } catch (StatusRuntimeException e) {
            logger.error("TTL failed for key={}: {}", key, e.getStatus());
            throw new CacheLibException("TTL failed: " + e.getStatus(), e);
        }
    }

    /**
     * Updates the TTL of a key without changing its value.
     * Useful for sliding window expiration and session extension.
     *
     * <p>Example - Session Extension:
     * <pre>{@code
     * // Extend session by 30 minutes on each request
     * if (client.touch("session:" + sessionId, 1800)) {
     *     // Session extended
     * } else {
     *     // Session not found, redirect to login
     * }
     * }</pre>
     *
     * @param key        the key to touch
     * @param ttlSeconds new TTL in seconds (0 = remove expiration)
     * @return true if the key exists and TTL was updated
     */
    public boolean touch(String key, long ttlSeconds) {
        logger.debug("TOUCH key={} ttl={}", key, ttlSeconds);
        try {
            TouchRequest request = TouchRequest.newBuilder()
                    .setKey(key)
                    .setTtlSeconds(ttlSeconds)
                    .build();
            TouchResponse response = blockingStub.touch(request);
            return response.getSuccess();
        } catch (StatusRuntimeException e) {
            logger.error("TOUCH failed for key={}: {}", key, e.getStatus());
            throw new CacheLibException("TOUCH failed: " + e.getStatus(), e);
        }
    }

    // =========================================================================
    // Administration
    // =========================================================================

    /**
     * Scans keys matching a pattern with pagination.
     * Useful for debugging and cache inspection.
     *
     * <p>Note: This operation may be slow on large caches.
     *
     * @param pattern the pattern to match (supports * as wildcard)
     * @param cursor  cursor from previous call (empty for first call)
     * @param count   maximum keys to return (default: 100)
     * @return ScanResult with matching keys and cursor for next page
     */
    public ScanResult scan(String pattern, String cursor, int count) {
        logger.debug("SCAN pattern={} cursor={} count={}", pattern, cursor, count);
        try {
            ScanRequest request = ScanRequest.newBuilder()
                    .setPattern(pattern)
                    .setCursor(cursor)
                    .setCount(count)
                    .build();
            ScanResponse response = blockingStub.scan(request);

            return new ScanResult(
                    response.getKeysList(),
                    response.getNextCursor(),
                    response.getHasMore()
            );
        } catch (StatusRuntimeException e) {
            logger.error("SCAN failed: {}", e.getStatus());
            throw new CacheLibException("SCAN failed: " + e.getStatus(), e);
        }
    }

    /**
     * Scans all keys matching a pattern.
     */
    public ScanResult scan(String pattern) {
        return scan(pattern, "", 100);
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
                    response.getDeleteCount(),
                    response.getEvictionCount(),
                    response.getNvmEnabled(),
                    response.getNvmSize(),
                    response.getNvmUsed(),
                    response.getNvmHitCount(),
                    response.getNvmMissCount(),
                    response.getUptimeSeconds(),
                    response.getVersion()
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

    /**
     * Flushes all keys from the cache.
     * Use with caution - this operation cannot be undone.
     *
     * @return number of items removed
     */
    public long flush() {
        return flush(false);
    }

    /**
     * Flushes all keys from the cache.
     *
     * @param includeNvm if true, also clear NVM cache
     * @return number of items removed
     */
    public long flush(boolean includeNvm) {
        logger.warn("FLUSH includeNvm={}", includeNvm);
        try {
            FlushRequest request = FlushRequest.newBuilder()
                    .setIncludeNvm(includeNvm)
                    .build();
            FlushResponse response = blockingStub.flush(request);

            if (!response.getSuccess()) {
                logger.error("FLUSH failed: {}", response.getMessage());
            }
            return response.getItemsRemoved();
        } catch (StatusRuntimeException e) {
            logger.error("FLUSH failed: {}", e.getStatus());
            throw new CacheLibException("FLUSH failed: " + e.getStatus(), e);
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

    // =========================================================================
    // Result Classes
    // =========================================================================

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
     * Result of a SetNX operation.
     */
    public static class SetNXResult {
        private final boolean wasSet;
        private final byte[] existingValue;
        private final String message;

        public SetNXResult(boolean wasSet, byte[] existingValue, String message) {
            this.wasSet = wasSet;
            this.existingValue = existingValue;
            this.message = message;
        }

        /** Returns true if the key was set (didn't exist before) */
        public boolean wasSet() {
            return wasSet;
        }

        /** Returns the existing value if wasSet=false */
        public byte[] getExistingValue() {
            return existingValue;
        }

        public String getExistingValueAsString() {
            return existingValue != null ? new String(existingValue, StandardCharsets.UTF_8) : null;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Result of an Increment or Decrement operation.
     */
    public static class IncrementResult {
        private final boolean success;
        private final long newValue;
        private final String message;

        public IncrementResult(boolean success, long newValue, String message) {
            this.success = success;
            this.newValue = newValue;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public long getNewValue() {
            return newValue;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Result of a CompareAndSwap operation.
     */
    public static class CompareAndSwapResult {
        private final boolean success;
        private final byte[] actualValue;
        private final String message;

        public CompareAndSwapResult(boolean success, byte[] actualValue, String message) {
            this.success = success;
            this.actualValue = actualValue;
            this.message = message;
        }

        /** Returns true if the swap was performed */
        public boolean wasSwapped() {
            return success;
        }

        /** Returns the actual value (useful if swap failed) */
        public byte[] getActualValue() {
            return actualValue;
        }

        public String getActualValueAsString() {
            return actualValue != null ? new String(actualValue, StandardCharsets.UTF_8) : null;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Result of a Scan operation.
     */
    public static class ScanResult {
        private final List<String> keys;
        private final String nextCursor;
        private final boolean hasMore;

        public ScanResult(List<String> keys, String nextCursor, boolean hasMore) {
            this.keys = keys;
            this.nextCursor = nextCursor;
            this.hasMore = hasMore;
        }

        public List<String> getKeys() {
            return keys;
        }

        public String getNextCursor() {
            return nextCursor;
        }

        public boolean hasMore() {
            return hasMore;
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
        private final long deleteCount;
        private final long evictionCount;
        private final boolean nvmEnabled;
        private final long nvmSize;
        private final long nvmUsed;
        private final long nvmHitCount;
        private final long nvmMissCount;
        private final long uptimeSeconds;
        private final String version;

        public CacheStats(long totalSize, long usedSize, long itemCount,
                         double hitRate, long getCount, long hitCount,
                         long missCount, long setCount, long deleteCount,
                         long evictionCount, boolean nvmEnabled, long nvmSize,
                         long nvmUsed, long nvmHitCount, long nvmMissCount,
                         long uptimeSeconds, String version) {
            this.totalSize = totalSize;
            this.usedSize = usedSize;
            this.itemCount = itemCount;
            this.hitRate = hitRate;
            this.getCount = getCount;
            this.hitCount = hitCount;
            this.missCount = missCount;
            this.setCount = setCount;
            this.deleteCount = deleteCount;
            this.evictionCount = evictionCount;
            this.nvmEnabled = nvmEnabled;
            this.nvmSize = nvmSize;
            this.nvmUsed = nvmUsed;
            this.nvmHitCount = nvmHitCount;
            this.nvmMissCount = nvmMissCount;
            this.uptimeSeconds = uptimeSeconds;
            this.version = version;
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
        public long getDeleteCount() { return deleteCount; }
        public long getEvictionCount() { return evictionCount; }
        public boolean isNvmEnabled() { return nvmEnabled; }
        public long getNvmSize() { return nvmSize; }
        public long getNvmUsed() { return nvmUsed; }
        public long getNvmHitCount() { return nvmHitCount; }
        public long getNvmMissCount() { return nvmMissCount; }
        public long getUptimeSeconds() { return uptimeSeconds; }
        public String getVersion() { return version; }

        @Override
        public String toString() {
            return String.format(
                    "CacheStats{version=%s, totalSize=%d, usedSize=%d, itemCount=%d, " +
                    "hitRate=%.2f%%, gets=%d, hits=%d, misses=%d, sets=%d, deletes=%d, " +
                    "evictions=%d, nvmEnabled=%s, uptime=%ds}",
                    version, totalSize, usedSize, itemCount, hitRate * 100,
                    getCount, hitCount, missCount, setCount, deleteCount,
                    evictionCount, nvmEnabled, uptimeSeconds
            );
        }
    }
}
