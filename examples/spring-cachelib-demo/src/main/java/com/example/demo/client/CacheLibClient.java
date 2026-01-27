package com.example.demo.client;

import com.facebook.cachelib.grpc.CacheServiceGrpc;
import com.facebook.cachelib.grpc.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * CacheLib gRPC client for Spring applications.
 * Thread-safe and ready for production use.
 *
 * Provides Redis-like operations including:
 * - Basic: get, set, delete, exists
 * - Batch: multiGet, multiSet
 * - Atomic: setNX, increment, decrement, compareAndSwap
 * - TTL: getTTL, touch
 * - Admin: ping, stats, flush
 */
public class CacheLibClient {

    private static final Logger log = LoggerFactory.getLogger(CacheLibClient.class);

    private final ManagedChannel channel;
    private final CacheServiceGrpc.CacheServiceBlockingStub blockingStub;

    public CacheLibClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.blockingStub = CacheServiceGrpc.newBlockingStub(channel);
        log.info("CacheLibClient connected to {}:{}", host, port);
    }

    // =========================================================================
    // Basic Operations
    // =========================================================================

    /**
     * Store a string value with TTL
     */
    public boolean set(String key, String value, int ttlSeconds) {
        return set(key, value.getBytes(StandardCharsets.UTF_8), ttlSeconds);
    }

    /**
     * Store a byte array value with TTL
     */
    public boolean set(String key, byte[] value, int ttlSeconds) {
        SetRequest request = SetRequest.newBuilder()
                .setKey(key)
                .setValue(ByteString.copyFrom(value))
                .setTtlSeconds(ttlSeconds)
                .build();

        SetResponse response = blockingStub.set(request);
        return response.getSuccess();
    }

    /**
     * Get a string value
     */
    public Optional<String> get(String key) {
        return getBytes(key).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Get a byte array value
     */
    public Optional<byte[]> getBytes(String key) {
        GetRequest request = GetRequest.newBuilder()
                .setKey(key)
                .build();

        GetResponse response = blockingStub.get(request);

        if (response.getFound()) {
            return Optional.of(response.getValue().toByteArray());
        }
        return Optional.empty();
    }

    /**
     * Get value with TTL information
     */
    public GetResponse getWithTTL(String key) {
        GetRequest request = GetRequest.newBuilder()
                .setKey(key)
                .build();
        return blockingStub.get(request);
    }

    /**
     * Delete a key
     */
    public boolean delete(String key) {
        DeleteRequest request = DeleteRequest.newBuilder()
                .setKey(key)
                .build();

        DeleteResponse response = blockingStub.delete(request);
        return response.getSuccess();
    }

    /**
     * Check if key exists
     */
    public boolean exists(String key) {
        ExistsRequest request = ExistsRequest.newBuilder()
                .setKey(key)
                .build();

        ExistsResponse response = blockingStub.exists(request);
        return response.getExists();
    }

    // =========================================================================
    // Batch Operations
    // =========================================================================

    /**
     * Get multiple keys at once
     */
    public Map<String, String> multiGet(Iterable<String> keys) {
        MultiGetRequest request = MultiGetRequest.newBuilder()
                .addAllKeys(keys)
                .build();

        MultiGetResponse response = blockingStub.multiGet(request);

        return response.getResultsList().stream()
                .filter(KeyValue::getFound)
                .collect(Collectors.toMap(
                        KeyValue::getKey,
                        r -> r.getValue().toStringUtf8()
                ));
    }

    /**
     * Get multiple keys with full response including TTL
     */
    public List<KeyValue> multiGetWithTTL(Iterable<String> keys) {
        MultiGetRequest request = MultiGetRequest.newBuilder()
                .addAllKeys(keys)
                .build();

        MultiGetResponse response = blockingStub.multiGet(request);
        return response.getResultsList();
    }

    /**
     * Set multiple key-value pairs at once
     */
    public MultiSetResponse multiSet(Map<String, String> items, int ttlSeconds) {
        MultiSetRequest.Builder requestBuilder = MultiSetRequest.newBuilder();

        for (Map.Entry<String, String> entry : items.entrySet()) {
            requestBuilder.addItems(SetRequest.newBuilder()
                    .setKey(entry.getKey())
                    .setValue(ByteString.copyFromUtf8(entry.getValue()))
                    .setTtlSeconds(ttlSeconds)
                    .build());
        }

        return blockingStub.multiSet(requestBuilder.build());
    }

    // =========================================================================
    // Atomic Operations
    // =========================================================================

    /**
     * Set if not exists (SETNX) - useful for distributed locks
     * Returns true if the key was set, false if it already existed
     */
    public boolean setNX(String key, String value, int ttlSeconds) {
        SetNXRequest request = SetNXRequest.newBuilder()
                .setKey(key)
                .setValue(ByteString.copyFromUtf8(value))
                .setTtlSeconds(ttlSeconds)
                .build();

        SetNXResponse response = blockingStub.setNX(request);
        return response.getWasSet();
    }

    /**
     * Set if not exists with full response
     */
    public SetNXResponse setNXWithResponse(String key, String value, int ttlSeconds) {
        SetNXRequest request = SetNXRequest.newBuilder()
                .setKey(key)
                .setValue(ByteString.copyFromUtf8(value))
                .setTtlSeconds(ttlSeconds)
                .build();

        return blockingStub.setNX(request);
    }

    /**
     * Atomically increment a numeric value
     * Creates key with value=delta if it doesn't exist
     */
    public long increment(String key, long delta) {
        return increment(key, delta, 0);
    }

    /**
     * Atomically increment with TTL for new keys
     */
    public long increment(String key, long delta, int ttlSeconds) {
        IncrementRequest request = IncrementRequest.newBuilder()
                .setKey(key)
                .setDelta(delta)
                .setTtlSeconds(ttlSeconds)
                .build();

        IncrementResponse response = blockingStub.increment(request);
        if (!response.getSuccess()) {
            throw new RuntimeException("Increment failed: " + response.getMessage());
        }
        return response.getNewValue();
    }

    /**
     * Atomically decrement a numeric value
     */
    public long decrement(String key, long delta) {
        return decrement(key, delta, 0);
    }

    /**
     * Atomically decrement with TTL for new keys
     */
    public long decrement(String key, long delta, int ttlSeconds) {
        DecrementRequest request = DecrementRequest.newBuilder()
                .setKey(key)
                .setDelta(delta)
                .setTtlSeconds(ttlSeconds)
                .build();

        DecrementResponse response = blockingStub.decrement(request);
        if (!response.getSuccess()) {
            throw new RuntimeException("Decrement failed: " + response.getMessage());
        }
        return response.getNewValue();
    }

    /**
     * Compare and swap - atomically update if current value matches expected
     * Returns true if swap was performed
     */
    public boolean compareAndSwap(String key, String expectedValue, String newValue) {
        return compareAndSwap(key, expectedValue, newValue, 0);
    }

    /**
     * Compare and swap with TTL
     */
    public boolean compareAndSwap(String key, String expectedValue, String newValue, int ttlSeconds) {
        CompareAndSwapRequest request = CompareAndSwapRequest.newBuilder()
                .setKey(key)
                .setExpectedValue(ByteString.copyFromUtf8(expectedValue))
                .setNewValue(ByteString.copyFromUtf8(newValue))
                .setTtlSeconds(ttlSeconds)
                .build();

        CompareAndSwapResponse response = blockingStub.compareAndSwap(request);
        return response.getSuccess();
    }

    /**
     * Compare and swap with full response
     */
    public CompareAndSwapResponse compareAndSwapWithResponse(String key, String expectedValue,
                                                              String newValue, int ttlSeconds) {
        CompareAndSwapRequest request = CompareAndSwapRequest.newBuilder()
                .setKey(key)
                .setExpectedValue(ByteString.copyFromUtf8(expectedValue))
                .setNewValue(ByteString.copyFromUtf8(newValue))
                .setTtlSeconds(ttlSeconds)
                .build();

        return blockingStub.compareAndSwap(request);
    }

    // =========================================================================
    // TTL Operations
    // =========================================================================

    /**
     * Get remaining TTL for a key
     * Returns: -2 if key not found, -1 if no expiry, 0+ seconds remaining
     */
    public long getTTL(String key) {
        GetTTLRequest request = GetTTLRequest.newBuilder()
                .setKey(key)
                .build();

        GetTTLResponse response = blockingStub.getTTL(request);
        return response.getTtlSeconds();
    }

    /**
     * Update TTL without changing the value (like Redis EXPIRE)
     */
    public boolean touch(String key, int ttlSeconds) {
        TouchRequest request = TouchRequest.newBuilder()
                .setKey(key)
                .setTtlSeconds(ttlSeconds)
                .build();

        TouchResponse response = blockingStub.touch(request);
        return response.getSuccess();
    }

    // =========================================================================
    // Administration
    // =========================================================================

    /**
     * Check if cache server is alive
     */
    public boolean ping() {
        try {
            PingRequest request = PingRequest.newBuilder().build();
            PingResponse response = blockingStub.ping(request);
            return "PONG".equalsIgnoreCase(response.getMessage());
        } catch (Exception e) {
            log.warn("Ping failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get cache statistics
     */
    public StatsResponse getStats() {
        StatsRequest request = StatsRequest.newBuilder().build();
        return blockingStub.stats(request);
    }

    /**
     * Get detailed cache statistics
     */
    public StatsResponse getDetailedStats() {
        StatsRequest request = StatsRequest.newBuilder()
                .setDetailed(true)
                .build();
        return blockingStub.stats(request);
    }

    /**
     * Flush all keys from cache
     */
    public FlushResponse flush() {
        FlushRequest request = FlushRequest.newBuilder().build();
        return blockingStub.flush(request);
    }

    /**
     * Scan keys matching a pattern
     * Pattern supports * wildcard (e.g., "user:*", "*:session")
     */
    public ScanResponse scan(String pattern, String cursor, int count) {
        ScanRequest request = ScanRequest.newBuilder()
                .setPattern(pattern)
                .setCursor(cursor != null ? cursor : "")
                .setCount(count > 0 ? count : 100)
                .build();

        return blockingStub.scan(request);
    }

    /**
     * Scan all keys matching a pattern (iterates through all pages)
     */
    public List<String> scanAll(String pattern, int maxKeys) {
        List<String> allKeys = new java.util.ArrayList<>();
        String cursor = "";

        while (allKeys.size() < maxKeys) {
            ScanResponse response = scan(pattern, cursor, Math.min(1000, maxKeys - allKeys.size()));
            allKeys.addAll(response.getKeysList());

            if (!response.getHasMore() || response.getNextCursor().isEmpty()) {
                break;
            }
            cursor = response.getNextCursor();
        }

        return allKeys;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CacheLibClient");
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
