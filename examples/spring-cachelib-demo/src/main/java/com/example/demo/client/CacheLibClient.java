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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * CacheLib gRPC client for Spring applications.
 * Thread-safe and ready for production use.
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
     * Check if cache server is alive
     */
    public boolean ping() {
        try {
            PingRequest request = PingRequest.newBuilder().build();
            PingResponse response = blockingStub.ping(request);
            return "pong".equals(response.getMessage());
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
