# CacheLib gRPC Java Client

A Java client library for connecting to the CacheLib gRPC server.

## Requirements

- Java 11 or later
- Maven 3.6+

## Building

```bash
mvn clean package
```

This will:
1. Generate Java code from the protobuf definition
2. Compile the client library
3. Create an executable JAR with all dependencies

## Running the Demo

First, make sure the CacheLib gRPC server is running:

```bash
# Using Docker
docker run -d -p 50051:50051 cachelib-grpc-server

# Or running locally
./cachelib-grpc-server --port=50051
```

Then run the demo:

```bash
# Default (localhost:50051)
java -jar target/cachelib-grpc-client-1.0.0-SNAPSHOT.jar

# Custom host and port
java -jar target/cachelib-grpc-client-1.0.0-SNAPSHOT.jar myhost.example.com 50051
```

## Using as a Library

Add the dependency to your project (after installing locally):

```xml
<dependency>
    <groupId>com.facebook.cachelib</groupId>
    <artifactId>cachelib-grpc-client</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Basic Usage

```java
import com.facebook.cachelib.grpc.client.CacheLibClient;
import java.util.Optional;

public class Example {
    public static void main(String[] args) {
        try (CacheLibClient client = new CacheLibClient("localhost", 50051)) {
            // Check connectivity
            if (!client.ping()) {
                System.err.println("Server not responding");
                return;
            }

            // Set a value (no expiration)
            client.set("user:123", "{\"name\": \"Alice\", \"age\": 30}");

            // Set with TTL (1 hour)
            client.set("session:abc", "token123", 3600);

            // Get a value
            Optional<String> user = client.getString("user:123");
            user.ifPresent(System.out::println);

            // Check if key exists
            boolean exists = client.exists("user:123");

            // Delete
            boolean deleted = client.delete("user:123");

            // Get statistics
            CacheLibClient.CacheStats stats = client.getStats();
            System.out.println("Hit rate: " + (stats.getHitRate() * 100) + "%");
        }
    }
}
```

### Batch Operations

```java
import java.util.*;

// Multi-set
Map<String, byte[]> items = new HashMap<>();
items.put("key1", "value1".getBytes());
items.put("key2", "value2".getBytes());
items.put("key3", "value3".getBytes());
int successCount = client.multiSet(items, 300);  // 5 min TTL

// Multi-get
List<String> keys = Arrays.asList("key1", "key2", "key3", "missing");
Map<String, Optional<byte[]>> results = client.multiGet(keys);

for (Map.Entry<String, Optional<byte[]>> entry : results.entrySet()) {
    if (entry.getValue().isPresent()) {
        System.out.println(entry.getKey() + " = " + new String(entry.getValue().get()));
    } else {
        System.out.println(entry.getKey() + " not found");
    }
}
```

### With TTL Information

```java
CacheLibClient.CacheEntry entry = client.getWithTtl("session:abc");
if (entry != null) {
    System.out.println("Value: " + entry.getValueAsString());
    System.out.println("TTL remaining: " + entry.getTtlRemaining() + " seconds");
}
```

### Error Handling

```java
try {
    client.set("key", "value");
} catch (CacheLibException e) {
    System.err.println("Cache operation failed: " + e.getMessage());
}
```

## API Reference

### CacheLibClient

| Method | Description |
|--------|-------------|
| `ping()` | Check server connectivity |
| `get(key)` | Get value as byte[] |
| `getString(key)` | Get value as String |
| `getWithTtl(key)` | Get value with TTL info |
| `set(key, value)` | Set with no expiration |
| `set(key, value, ttlSeconds)` | Set with TTL |
| `delete(key)` | Delete a key |
| `exists(key)` | Check if key exists |
| `multiGet(keys)` | Batch get |
| `multiSet(entries)` | Batch set |
| `multiSet(entries, ttlSeconds)` | Batch set with TTL |
| `getStats()` | Get cache statistics |

### CacheStats

| Field | Type | Description |
|-------|------|-------------|
| `totalSize` | long | Total cache size in bytes |
| `usedSize` | long | Used cache size in bytes |
| `itemCount` | long | Number of items |
| `hitRate` | double | Cache hit rate (0.0-1.0) |
| `getCount` | long | Total get operations |
| `hitCount` | long | Cache hits |
| `missCount` | long | Cache misses |
| `setCount` | long | Total set operations |
| `evictionCount` | long | Items evicted |
| `nvmEnabled` | boolean | NVM cache enabled |
| `nvmSize` | long | NVM cache size |
| `uptimeSeconds` | long | Server uptime |

## Configuration

### Connection Settings

```java
// Custom channel configuration
ManagedChannel channel = ManagedChannelBuilder
    .forAddress("cachelib-server", 50051)
    .usePlaintext()
    .maxInboundMessageSize(10 * 1024 * 1024)  // 10MB max message
    .keepAliveTime(30, TimeUnit.SECONDS)
    .keepAliveTimeout(10, TimeUnit.SECONDS)
    .build();

CacheLibClient client = new CacheLibClient(channel);
```

### TLS Configuration

```java
ManagedChannel channel = ManagedChannelBuilder
    .forAddress("cachelib-server", 50051)
    .useTransportSecurity()
    .build();
```

## Thread Safety

The `CacheLibClient` is thread-safe and can be shared across multiple threads. The underlying gRPC channel handles connection pooling automatically.

```java
// Shared client instance
private static final CacheLibClient CACHE_CLIENT =
    new CacheLibClient("localhost", 50051);

// Safe to use from multiple threads
ExecutorService executor = Executors.newFixedThreadPool(10);
for (int i = 0; i < 100; i++) {
    final int idx = i;
    executor.submit(() -> {
        CACHE_CLIENT.set("key-" + idx, "value-" + idx);
    });
}
```

## License

Apache 2.0 - See [LICENSE](../../LICENSE)
