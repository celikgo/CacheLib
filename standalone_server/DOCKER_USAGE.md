# CacheLib gRPC Server - Docker Usage Guide

A high-performance caching server from Meta/Facebook, available as a Docker container. Use it as an alternative to Redis with superior performance and hybrid DRAM+SSD caching capabilities.

**Version:** 1.1.0

## Quick Start

### Pull and Run

```bash
docker pull ghcr.io/celikgo/cachelib-grpc-server:latest

# Run with 1 GB cache (default)
docker run -d --name cachelib -p 50051:50051 ghcr.io/celikgo/cachelib-grpc-server:latest

# Run with custom cache size (2 GB)
docker run -d --name cachelib -p 50051:50051 ghcr.io/celikgo/cachelib-grpc-server:latest \
  --address=0.0.0.0 --port=50051 --cache_size=2147483648
```

### Docker Compose

```yaml
services:
  cachelib:
    image: ghcr.io/celikgo/cachelib-grpc-server:latest
    ports:
      - "50051:50051"
    command:
      - "--address=0.0.0.0"
      - "--port=50051"
      - "--cache_size=1073741824"  # 1 GB
    restart: unless-stopped
```

## Server Options

| Argument | Default | Description |
|----------|---------|-------------|
| `--address` | `0.0.0.0` | Listen address |
| `--port` | `50051` | gRPC port |
| `--cache_size` | `1073741824` | Cache size in bytes (1 GB) |
| `--enable_nvm` | `false` | Enable SSD cache |
| `--nvm_path` | `/data/nvm/cache` | SSD cache file path |
| `--nvm_size` | `10737418240` | SSD cache size (10 GB) |

### Enable Hybrid DRAM+SSD Cache

For larger datasets, enable NVM (SSD) caching:

```yaml
services:
  cachelib:
    image: ghcr.io/celikgo/cachelib-grpc-server:latest
    ports:
      - "50051:50051"
    command:
      - "--address=0.0.0.0"
      - "--port=50051"
      - "--cache_size=1073741824"    # 1 GB DRAM
      - "--enable_nvm=true"
      - "--nvm_path=/data/nvm/cache"
      - "--nvm_size=10737418240"     # 10 GB SSD
    volumes:
      - nvm-data:/data/nvm

volumes:
  nvm-data:
```

## API Reference

### Basic Operations

| Operation | Description | Redis Equivalent |
|-----------|-------------|------------------|
| `Get` | Retrieve a value | `GET` |
| `Set` | Store a value with TTL | `SET` / `SETEX` |
| `Delete` | Remove a key | `DEL` |
| `Exists` | Check if key exists | `EXISTS` |

### Batch Operations

| Operation | Description | Redis Equivalent |
|-----------|-------------|------------------|
| `MultiGet` | Get multiple keys | `MGET` |
| `MultiSet` | Set multiple keys | `MSET` |

### Atomic Operations

| Operation | Description | Redis Equivalent |
|-----------|-------------|------------------|
| `SetNX` | Set if not exists | `SETNX` |
| `Increment` | Atomic increment | `INCR` / `INCRBY` |
| `Decrement` | Atomic decrement | `DECR` / `DECRBY` |
| `CompareAndSwap` | Atomic CAS | (Lua script) |

### TTL Operations

| Operation | Description | Redis Equivalent |
|-----------|-------------|------------------|
| `GetTTL` | Get remaining TTL | `TTL` |
| `Touch` | Update TTL | `EXPIRE` |

### Administration

| Operation | Description | Redis Equivalent |
|-----------|-------------|------------------|
| `Ping` | Health check | `PING` |
| `Stats` | Cache statistics | `INFO` |
| `Flush` | Clear all keys | `FLUSHALL` |
| `Scan` | Iterate keys | `SCAN` |

## Proto File

Save as `cache.proto`:

```protobuf
syntax = "proto3";
package cachelib.grpc;
option java_package = "com.facebook.cachelib.grpc";

service CacheService {
  // Basic Operations
  rpc Get(GetRequest) returns (GetResponse);
  rpc Set(SetRequest) returns (SetResponse);
  rpc Delete(DeleteRequest) returns (DeleteResponse);
  rpc Exists(ExistsRequest) returns (ExistsResponse);

  // Batch Operations
  rpc MultiGet(MultiGetRequest) returns (MultiGetResponse);
  rpc MultiSet(MultiSetRequest) returns (MultiSetResponse);

  // Atomic Operations
  rpc SetNX(SetNXRequest) returns (SetNXResponse);
  rpc Increment(IncrementRequest) returns (IncrementResponse);
  rpc Decrement(DecrementRequest) returns (DecrementResponse);
  rpc CompareAndSwap(CompareAndSwapRequest) returns (CompareAndSwapResponse);

  // TTL Operations
  rpc GetTTL(GetTTLRequest) returns (GetTTLResponse);
  rpc Touch(TouchRequest) returns (TouchResponse);

  // Administration
  rpc Ping(PingRequest) returns (PingResponse);
  rpc Stats(StatsRequest) returns (StatsResponse);
  rpc Flush(FlushRequest) returns (FlushResponse);
  rpc Scan(ScanRequest) returns (ScanResponse);
}

// Basic Operations
message GetRequest { string key = 1; }
message GetResponse {
  bool found = 1;
  bytes value = 2;
  int64 ttl_remaining = 3;  // -1 = no expiry
}

message SetRequest {
  string key = 1;
  bytes value = 2;
  int64 ttl_seconds = 3;  // 0 = no expiry
}
message SetResponse { bool success = 1; string message = 2; }

message DeleteRequest { string key = 1; }
message DeleteResponse { bool success = 1; bool key_existed = 2; string message = 3; }

message ExistsRequest { string key = 1; }
message ExistsResponse { bool exists = 1; }

// Batch Operations
message MultiGetRequest { repeated string keys = 1; }
message MultiGetResponse { repeated KeyValue results = 1; }
message KeyValue { string key = 1; bytes value = 2; bool found = 3; int64 ttl_remaining = 4; }

message MultiSetRequest { repeated SetRequest items = 1; }
message MultiSetResponse {
  bool success = 1;
  int32 succeeded_count = 2;
  int32 failed_count = 3;
  string message = 4;
  repeated string failed_keys = 5;
}

// Atomic Operations
message SetNXRequest { string key = 1; bytes value = 2; int64 ttl_seconds = 3; }
message SetNXResponse { bool was_set = 1; bytes existing_value = 2; string message = 3; }

message IncrementRequest { string key = 1; int64 delta = 2; int64 ttl_seconds = 3; }
message IncrementResponse { bool success = 1; int64 new_value = 2; string message = 3; }

message DecrementRequest { string key = 1; int64 delta = 2; int64 ttl_seconds = 3; }
message DecrementResponse { bool success = 1; int64 new_value = 2; string message = 3; }

message CompareAndSwapRequest {
  string key = 1;
  bytes expected_value = 2;
  bytes new_value = 3;
  int64 ttl_seconds = 4;
}
message CompareAndSwapResponse { bool success = 1; bytes actual_value = 2; string message = 3; }

// TTL Operations
message GetTTLRequest { string key = 1; }
message GetTTLResponse {
  bool found = 1;
  int64 ttl_seconds = 2;  // -2 = not found, -1 = no expiry, 0+ = seconds
}

message TouchRequest { string key = 1; int64 ttl_seconds = 2; }
message TouchResponse { bool success = 1; string message = 2; }

// Administration
message PingRequest {}
message PingResponse { string message = 1; int64 timestamp = 2; }

message StatsRequest { bool detailed = 1; }
message StatsResponse {
  int64 total_size = 1;
  int64 used_size = 2;
  int64 item_count = 3;
  double hit_rate = 4;
  int64 get_count = 5;
  int64 hit_count = 6;
  int64 miss_count = 7;
  int64 set_count = 8;
  int64 delete_count = 9;
  int64 eviction_count = 10;
  int64 expired_count = 11;
  bool nvm_enabled = 12;
  int64 nvm_size = 13;
  int64 nvm_used = 14;
  int64 nvm_hit_count = 15;
  int64 nvm_miss_count = 16;
  int64 uptime_seconds = 17;
  string version = 18;
}

message FlushRequest { bool include_nvm = 1; }
message FlushResponse { bool success = 1; int64 items_removed = 2; string message = 3; }

message ScanRequest { string pattern = 1; string cursor = 2; int32 count = 3; }
message ScanResponse { repeated string keys = 1; string next_cursor = 2; bool has_more = 3; }
```

## Client Examples

### Python

```bash
pip install grpcio grpcio-tools
python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. cache.proto
```

```python
import grpc
from cache_pb2 import *
from cache_pb2_grpc import CacheServiceStub

channel = grpc.insecure_channel('localhost:50051')
cache = CacheServiceStub(channel)

# Basic Operations
cache.Set(SetRequest(key="user:1", value=b'{"name":"John"}', ttl_seconds=300))
response = cache.Get(GetRequest(key="user:1"))
if response.found:
    print(response.value.decode())
    print(f"TTL: {response.ttl_remaining}s")

# Atomic Increment (rate limiting example)
response = cache.Increment(IncrementRequest(key="ratelimit:user:1", delta=1, ttl_seconds=60))
if response.success:
    print(f"Request count: {response.new_value}")

# SetNX (distributed lock example)
response = cache.SetNX(SetNXRequest(key="lock:resource", value=b"owner-1", ttl_seconds=30))
if response.was_set:
    print("Lock acquired!")
else:
    print("Lock held by another process")

# TTL Operations
ttl = cache.GetTTL(GetTTLRequest(key="user:1"))
print(f"TTL remaining: {ttl.ttl_seconds}s")

cache.Touch(TouchRequest(key="user:1", ttl_seconds=600))  # Extend TTL

# Stats
stats = cache.Stats(StatsRequest())
print(f"Hit rate: {stats.hit_rate:.2%}")
print(f"Items: {stats.item_count}")
print(f"Uptime: {stats.uptime_seconds}s")
print(f"Version: {stats.version}")
```

### Java / Spring Boot

```java
import com.facebook.cachelib.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import com.google.protobuf.ByteString;

public class CacheClient {
    private final CacheServiceGrpc.CacheServiceBlockingStub stub;

    public CacheClient(String host, int port) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext().build();
        this.stub = CacheServiceGrpc.newBlockingStub(channel);
    }

    // Basic Operations
    public void set(String key, String value, int ttl) {
        stub.set(SetRequest.newBuilder()
            .setKey(key)
            .setValue(ByteString.copyFromUtf8(value))
            .setTtlSeconds(ttl)
            .build());
    }

    public String get(String key) {
        GetResponse resp = stub.get(GetRequest.newBuilder().setKey(key).build());
        return resp.getFound() ? resp.getValue().toStringUtf8() : null;
    }

    // Atomic Increment
    public long increment(String key, long delta, int ttl) {
        IncrementResponse resp = stub.increment(IncrementRequest.newBuilder()
            .setKey(key)
            .setDelta(delta)
            .setTtlSeconds(ttl)
            .build());
        return resp.getNewValue();
    }

    // Distributed Lock with SetNX
    public boolean acquireLock(String resource, String owner, int ttlSeconds) {
        SetNXResponse resp = stub.setNX(SetNXRequest.newBuilder()
            .setKey("lock:" + resource)
            .setValue(ByteString.copyFromUtf8(owner))
            .setTtlSeconds(ttlSeconds)
            .build());
        return resp.getWasSet();
    }

    // Get TTL
    public long getTTL(String key) {
        GetTTLResponse resp = stub.getTTL(GetTTLRequest.newBuilder().setKey(key).build());
        return resp.getTtlSeconds();
    }

    // Extend TTL
    public boolean touch(String key, int newTtl) {
        TouchResponse resp = stub.touch(TouchRequest.newBuilder()
            .setKey(key)
            .setTtlSeconds(newTtl)
            .build());
        return resp.getSuccess();
    }
}
```

### Go

```go
package main

import (
    "context"
    "log"
    pb "your-module/cache"
    "google.golang.org/grpc"
    "google.golang.org/grpc/credentials/insecure"
)

func main() {
    conn, _ := grpc.Dial("localhost:50051", grpc.WithTransportCredentials(insecure.NewCredentials()))
    defer conn.Close()
    client := pb.NewCacheServiceClient(conn)
    ctx := context.Background()

    // Basic Operations
    client.Set(ctx, &pb.SetRequest{Key: "user:1", Value: []byte(`{"name":"John"}`), TtlSeconds: 300})

    resp, _ := client.Get(ctx, &pb.GetRequest{Key: "user:1"})
    if resp.Found {
        log.Printf("Value: %s, TTL: %d", resp.Value, resp.TtlRemaining)
    }

    // Atomic Increment
    incrResp, _ := client.Increment(ctx, &pb.IncrementRequest{Key: "counter", Delta: 1, TtlSeconds: 3600})
    log.Printf("Counter: %d", incrResp.NewValue)

    // SetNX for distributed lock
    lockResp, _ := client.SetNX(ctx, &pb.SetNXRequest{Key: "lock:resource", Value: []byte("owner-1"), TtlSeconds: 30})
    if lockResp.WasSet {
        log.Println("Lock acquired!")
    }

    // Stats
    stats, _ := client.Stats(ctx, &pb.StatsRequest{})
    log.Printf("Version: %s, Uptime: %ds, Hit rate: %.2f%%",
        stats.Version, stats.UptimeSeconds, stats.HitRate*100)
}
```

### Node.js

```javascript
const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');

const packageDef = protoLoader.loadSync('cache.proto', {
  keepCase: true, longs: String, enums: String, defaults: true, oneofs: true
});
const proto = grpc.loadPackageDefinition(packageDef).cachelib.grpc;
const client = new proto.CacheService('localhost:50051', grpc.credentials.createInsecure());

// Basic Operations
client.Set({ key: 'user:1', value: Buffer.from('{"name":"John"}'), ttl_seconds: 300 },
  (err, resp) => console.log('Set:', resp.success));

client.Get({ key: 'user:1' }, (err, resp) => {
  if (resp.found) console.log('Value:', resp.value.toString(), 'TTL:', resp.ttl_remaining);
});

// Atomic Increment
client.Increment({ key: 'counter', delta: 1, ttl_seconds: 3600 }, (err, resp) => {
  console.log('Counter:', resp.new_value);
});

// SetNX for distributed lock
client.SetNX({ key: 'lock:resource', value: Buffer.from('owner-1'), ttl_seconds: 30 },
  (err, resp) => console.log('Lock acquired:', resp.was_set));

// Stats
client.Stats({}, (err, resp) => {
  console.log(`Version: ${resp.version}, Uptime: ${resp.uptime_seconds}s`);
  console.log(`Hit rate: ${(resp.hit_rate * 100).toFixed(2)}%`);
});
```

## Use Cases

### Rate Limiting

```python
def check_rate_limit(user_id, limit=100, window=60):
    key = f"ratelimit:{user_id}"
    resp = cache.Increment(IncrementRequest(key=key, delta=1, ttl_seconds=window))
    return resp.new_value <= limit
```

### Distributed Lock

```python
def acquire_lock(resource, owner, ttl=30):
    resp = cache.SetNX(SetNXRequest(
        key=f"lock:{resource}",
        value=owner.encode(),
        ttl_seconds=ttl
    ))
    return resp.was_set

def release_lock(resource, owner):
    # Use CAS to ensure we only release our own lock
    resp = cache.CompareAndSwap(CompareAndSwapRequest(
        key=f"lock:{resource}",
        expected_value=owner.encode(),
        new_value=b""
    ))
    if resp.success:
        cache.Delete(DeleteRequest(key=f"lock:{resource}"))
```

### Session Management with Sliding Expiration

```python
def get_session(session_id):
    resp = cache.Get(GetRequest(key=f"session:{session_id}"))
    if resp.found:
        # Extend session TTL on access
        cache.Touch(TouchRequest(key=f"session:{session_id}", ttl_seconds=1800))
        return resp.value.decode()
    return None
```

## Full Example Projects

- **Spring Boot Demo**: https://github.com/celikgo/CacheLib/tree/main/examples/spring-cachelib-demo

## Comparison with Redis

| Feature | Redis | CacheLib |
|---------|-------|----------|
| Protocol | RESP | gRPC |
| Hybrid Cache | No | Yes (DRAM+SSD) |
| SetNX | ✅ | ✅ |
| INCR/DECR | ✅ | ✅ |
| TTL/EXPIRE | ✅ | ✅ |
| CAS | Via Lua | ✅ Native |
| Clustering | Built-in | Single node |

## Troubleshooting

### Stats show 0 for counters

Ensure you're checking after operations. Stats are tracked per-server instance and reset on restart.

### Connection refused

```bash
docker ps | grep cachelib
docker logs cachelib
nc -zv localhost 50051
```

## Links

- [CacheLib Documentation](https://cachelib.org/)
- [gRPC Documentation](https://grpc.io/docs/)

## License

Apache License 2.0
