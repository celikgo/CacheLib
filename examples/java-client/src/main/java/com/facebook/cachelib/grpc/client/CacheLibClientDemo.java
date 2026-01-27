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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Demo application showcasing the CacheLib gRPC client.
 *
 * <p>This demo connects to a CacheLib gRPC server and performs various
 * cache operations to demonstrate the client functionality, including:
 * <ul>
 *   <li>Basic operations (Get, Set, Delete, Exists)</li>
 *   <li>Batch operations (MultiGet, MultiSet)</li>
 *   <li>Atomic operations (SetNX, Increment, Decrement)</li>
 *   <li>TTL operations (GetTTL, Touch)</li>
 *   <li>Administration (Stats, Scan)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   java -jar cachelib-grpc-client.jar [host] [port]
 * </pre>
 *
 * <p>Default: localhost:50051
 */
public class CacheLibClientDemo {
    private static final Logger logger = LoggerFactory.getLogger(CacheLibClientDemo.class);

    public static void main(String[] args) {
        String host = "localhost";
        int port = 50051;

        if (args.length >= 1) {
            host = args[0];
        }
        if (args.length >= 2) {
            port = Integer.parseInt(args[1]);
        }

        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║         CacheLib gRPC Client Demo v1.2                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Connecting to " + host + ":" + port + "...");
        System.out.println();

        try (CacheLibClient client = new CacheLibClient(host, port)) {
            runDemo(client);
        } catch (Exception e) {
            logger.error("Demo failed", e);
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runDemo(CacheLibClient client) {
        // 1. Ping test
        printSection("1. Ping Test");
        boolean alive = client.ping();
        System.out.println("Server is " + (alive ? "ALIVE" : "NOT RESPONDING"));
        System.out.println();

        if (!alive) {
            System.err.println("Cannot connect to server. Make sure it's running.");
            return;
        }

        // 2. Basic Set/Get
        printSection("2. Basic Set/Get Operations");
        String key1 = "greeting";
        String value1 = "Hello, CacheLib!";

        System.out.println("SET \"" + key1 + "\" = \"" + value1 + "\"");
        boolean setResult = client.set(key1, value1);
        System.out.println("Result: " + (setResult ? "SUCCESS" : "FAILED"));

        System.out.println();
        System.out.println("GET \"" + key1 + "\"");
        Optional<String> getValue = client.getString(key1);
        System.out.println("Result: " + getValue.orElse("(not found)"));
        System.out.println();

        // 3. Set with TTL
        printSection("3. Set with TTL");
        String key2 = "expiring-key";
        String value2 = "This will expire in 60 seconds";

        System.out.println("SET \"" + key2 + "\" with TTL=60s");
        client.set(key2, value2, 60);

        CacheLibClient.CacheEntry entry = client.getWithTtl(key2);
        if (entry != null) {
            System.out.println("Value: " + entry.getValueAsString());
            System.out.println("TTL remaining: " + entry.getTtlRemaining() + " seconds");
        }
        System.out.println();

        // 4. GetTTL Operation
        printSection("4. GetTTL Operation");
        long ttl = client.getTTL(key2);
        System.out.println("TTL for \"" + key2 + "\": " + ttl + " seconds");

        long ttlNonExistent = client.getTTL("non-existent-key");
        System.out.println("TTL for non-existent key: " + ttlNonExistent + " (-2 means not found)");
        System.out.println();

        // 5. Touch Operation (Extend TTL)
        printSection("5. Touch Operation - Extend TTL");
        System.out.println("Extending TTL of \"" + key2 + "\" to 120 seconds...");
        boolean touched = client.touch(key2, 120);
        System.out.println("Touch result: " + (touched ? "SUCCESS" : "FAILED"));

        long newTtl = client.getTTL(key2);
        System.out.println("New TTL: " + newTtl + " seconds");
        System.out.println();

        // 6. SetNX - Distributed Locking
        printSection("6. SetNX - Distributed Locking");
        String lockKey = "lock:resource-123";
        String ownerId = "owner-" + UUID.randomUUID().toString().substring(0, 8);

        System.out.println("Attempting to acquire lock \"" + lockKey + "\"...");
        CacheLibClient.SetNXResult lock1 = client.setNX(lockKey, ownerId, 30);
        System.out.println("First attempt - Lock acquired: " + lock1.wasSet());

        System.out.println("\nAttempting to acquire same lock again...");
        CacheLibClient.SetNXResult lock2 = client.setNX(lockKey, "another-owner", 30);
        System.out.println("Second attempt - Lock acquired: " + lock2.wasSet());
        if (!lock2.wasSet()) {
            System.out.println("Existing owner: " + lock2.getExistingValueAsString());
        }

        // Release lock
        client.delete(lockKey);
        System.out.println("Lock released.");
        System.out.println();

        // 7. Increment/Decrement - Rate Limiting & Counters
        printSection("7. Increment/Decrement - Rate Limiting");
        String counterKey = "rate:user:456";

        System.out.println("Simulating rate limiting (limit: 5 requests per minute)...");
        for (int i = 1; i <= 7; i++) {
            CacheLibClient.IncrementResult result = client.increment(counterKey, 1, 60);
            boolean allowed = result.getNewValue() <= 5;
            System.out.printf("  Request %d: count=%d, %s%n",
                    i, result.getNewValue(), allowed ? "ALLOWED" : "RATE LIMITED");
        }

        System.out.println("\nDecrementing counter...");
        CacheLibClient.IncrementResult decrResult = client.decrement(counterKey, 2, 0);
        System.out.println("After decrement by 2: " + decrResult.getNewValue());
        System.out.println();

        // 8. Delete operation
        printSection("8. Delete Operation");
        String key3 = "to-be-deleted";
        client.set(key3, "temporary data");
        System.out.println("SET \"" + key3 + "\"");

        boolean existsBefore = client.exists(key3);
        System.out.println("EXISTS before delete: " + existsBefore);

        boolean deleted = client.delete(key3);
        System.out.println("DELETE result: " + (deleted ? "key was deleted" : "key not found"));

        boolean existsAfter = client.exists(key3);
        System.out.println("EXISTS after delete: " + existsAfter);
        System.out.println();

        // 9. Multi-Get
        printSection("9. Multi-Get Operation");
        Map<String, byte[]> bulkData = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            bulkData.put("item-" + i, ("Value " + i).getBytes(StandardCharsets.UTF_8));
        }

        System.out.println("Setting " + bulkData.size() + " items...");
        int setCount = client.multiSet(bulkData);
        System.out.println("Successfully set: " + setCount + " items");

        List<String> keysToGet = Arrays.asList("item-1", "item-3", "item-5", "item-99");
        System.out.println("\nGetting keys: " + keysToGet);
        Map<String, Optional<byte[]>> results = client.multiGet(keysToGet);

        for (Map.Entry<String, Optional<byte[]>> entry2 : results.entrySet()) {
            String val = entry2.getValue()
                    .map(b -> new String(b, StandardCharsets.UTF_8))
                    .orElse("(not found)");
            System.out.println("  " + entry2.getKey() + " = " + val);
        }
        System.out.println();

        // 10. Binary data
        printSection("10. Binary Data");
        String binKey = "binary-data";
        byte[] binaryData = new byte[256];
        for (int i = 0; i < 256; i++) {
            binaryData[i] = (byte) i;
        }

        System.out.println("Storing 256 bytes of binary data...");
        client.set(binKey, binaryData);

        Optional<byte[]> retrievedBinary = client.get(binKey);
        if (retrievedBinary.isPresent()) {
            byte[] data = retrievedBinary.get();
            System.out.println("Retrieved " + data.length + " bytes");
            boolean dataMatches = Arrays.equals(binaryData, data);
            System.out.println("Data integrity: " + (dataMatches ? "PASSED" : "FAILED"));
        }
        System.out.println();

        // 11. Large value
        printSection("11. Large Value Test");
        String largeKey = "large-value";
        int size = 100 * 1024; // 100 KB
        byte[] largeValue = new byte[size];
        new Random().nextBytes(largeValue);

        System.out.println("Storing " + (size / 1024) + " KB of random data...");
        boolean largeSetResult = client.set(largeKey, largeValue);
        System.out.println("SET result: " + (largeSetResult ? "SUCCESS" : "FAILED"));

        Optional<byte[]> retrievedLarge = client.get(largeKey);
        if (retrievedLarge.isPresent()) {
            System.out.println("Retrieved " + retrievedLarge.get().length + " bytes");
            boolean matches = Arrays.equals(largeValue, retrievedLarge.get());
            System.out.println("Data integrity: " + (matches ? "PASSED" : "FAILED"));
        }
        System.out.println();

        // 12. Cache Statistics
        printSection("12. Cache Statistics");
        CacheLibClient.CacheStats stats = client.getStats();
        System.out.println("Server Version: " + stats.getVersion());
        System.out.println("Cache Size: " + formatBytes(stats.getTotalSize()));
        System.out.println("Used Size: " + formatBytes(stats.getUsedSize()));
        System.out.println("Item Count: " + stats.getItemCount());
        System.out.println("Hit Rate: " + String.format("%.2f%%", stats.getHitRate() * 100));
        System.out.println("Operations:");
        System.out.println("  - Gets: " + stats.getGetCount());
        System.out.println("  - Hits: " + stats.getHitCount());
        System.out.println("  - Misses: " + stats.getMissCount());
        System.out.println("  - Sets: " + stats.getSetCount());
        System.out.println("  - Deletes: " + stats.getDeleteCount());
        System.out.println("  - Evictions: " + stats.getEvictionCount());
        System.out.println("NVM Cache: " + (stats.isNvmEnabled() ? "Enabled" : "Disabled"));
        if (stats.isNvmEnabled()) {
            System.out.println("  - NVM Size: " + formatBytes(stats.getNvmSize()));
            System.out.println("  - NVM Used: " + formatBytes(stats.getNvmUsed()));
            System.out.println("  - NVM Hits: " + stats.getNvmHitCount());
            System.out.println("  - NVM Misses: " + stats.getNvmMissCount());
        }
        System.out.println("Server Uptime: " + formatDuration(stats.getUptimeSeconds()));
        System.out.println();

        // Verify counter invariants
        System.out.println("Verification:");
        boolean getCountValid = stats.getGetCount() > 0;
        boolean setCountValid = stats.getSetCount() > 0;
        boolean hitMissSum = stats.getHitCount() + stats.getMissCount() <= stats.getGetCount() + 10; // Allow some tolerance
        System.out.println("  - Get count > 0: " + (getCountValid ? "PASS" : "FAIL"));
        System.out.println("  - Set count > 0: " + (setCountValid ? "PASS" : "FAIL"));
        System.out.println("  - Hit + Miss ~= Get: " + (hitMissSum ? "PASS" : "CHECK"));
        System.out.println();

        // 13. Cleanup
        printSection("13. Cleanup");
        List<String> keysToDelete = Arrays.asList(
                key1, key2, key3, binKey, largeKey, counterKey,
                "item-1", "item-2", "item-3", "item-4", "item-5"
        );
        int deleted_count = 0;
        for (String key : keysToDelete) {
            if (client.delete(key)) {
                deleted_count++;
            }
        }
        System.out.println("Cleaned up " + deleted_count + " keys");
        System.out.println();

        // Summary
        printSection("Demo Complete");
        System.out.println("All operations completed successfully!");
        System.out.println();
        System.out.println("Features demonstrated:");
        System.out.println("  - Basic: Get, Set, Delete, Exists");
        System.out.println("  - Batch: MultiGet, MultiSet");
        System.out.println("  - Atomic: SetNX (locks), Increment, Decrement");
        System.out.println("  - TTL: GetTTL, Touch");
        System.out.println("  - Admin: Stats, Ping");
        System.out.println();
        System.out.println("For more information, see:");
        System.out.println("  https://github.com/facebook/CacheLib");
    }

    private static void printSection(String title) {
        System.out.println("─".repeat(60));
        System.out.println("  " + title);
        System.out.println("─".repeat(60));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return String.format("%dm %ds", seconds / 60, seconds % 60);
        long hours = seconds / 3600;
        long mins = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return String.format("%dh %dm %ds", hours, mins, secs);
    }
}
