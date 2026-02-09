# CacheLib gRPC Server v1.2.2

A high-performance, Redis-compatible caching server powered by [CacheLib](https://github.com/facebook/CacheLib) with gRPC interface.

## Quick Start

### Using Pre-built Docker Image (Recommended)

```bash
# Pull the latest image
docker pull ghcr.io/celikgo/cachelib-grpc-server:1.2.2

# Run the server (1GB RAM cache)
docker run -d --name cachelib -p 50051:50051 ghcr.io/celikgo/cachelib-grpc-server:1.2.2

# Run with custom cache size (4GB)
docker run -d --name cachelib -p 50051:50051 \
  ghcr.io/celikgo/cachelib-grpc-server:1.2.2 \
  --cache_size=4294967296

# Run with hybrid caching (DRAM + SSD)
docker run -d --name cachelib -p 50051:50051 \
  -v /path/to/ssd:/data/nvm \
  ghcr.io/celikgo/cachelib-grpc-server:1.2.2 \
  --cache_size=2147483648 \
  --enable_nvm=true \
  --nvm_path=/data/nvm/cache.dat \
  --nvm_size=10737418240
```

### Verify Installation

```bash
# Health check
grpcurl -plaintext localhost:50051 cachelib.grpc.CacheService/Ping

# Response: {"message":"PONG","timestamp":"1706000000000"}
```

## Features

### Core Operations
| Operation | Description |
|-----------|-------------|
| **Set** | Store key-value with optional TTL |
| **Get** | Retrieve value by key |
| **Delete** | Remove key from cache |
| **Exists** | Check if key exists |
| **MultiGet** | Batch retrieve multiple keys |
| **MultiSet** | Batch store multiple key-values |

### Redis-Parity Features (v1.2.0+)
| Feature | Description | Use Case |
|---------|-------------|----------|
| **GetTTL** | Check remaining TTL | Cache monitoring |
| **SetNX** | Set if not exists | Distributed locks |
| **Increment** | Atomic add | Rate limiting, counters |
| **Decrement** | Atomic subtract | Counters |
| **Touch** | Update TTL only | Session extension |
| **CompareAndSwap** | Conditional update | Optimistic locking |

### Administration
| Operation | Description |
|-----------|-------------|
| **Ping** | Health check |
| **Stats** | Cache statistics and metrics |
| **Flush** | Clear all keys |

## API Examples

### Basic Operations

```bash
# SET with TTL (60 seconds)
grpcurl -plaintext -d '{"key":"user:123","value":"eyJuYW1lIjoiSm9obiJ9","ttl_seconds":60}' \
  localhost:50051 cachelib.grpc.CacheService/Set

# GET
grpcurl -plaintext -d '{"key":"user:123"}' \
  localhost:50051 cachelib.grpc.CacheService/Get

# DELETE
grpcurl -plaintext -d '{"key":"user:123"}' \
  localhost:50051 cachelib.grpc.CacheService/Delete

# EXISTS
grpcurl -plaintext -d '{"key":"user:123"}' \
  localhost:50051 cachelib.grpc.CacheService/Exists

# PING
grpcurl -plaintext localhost:50051 cachelib.grpc.CacheService/Ping
```

### Distributed Locking (SetNX)

```bash
# Acquire lock (returns wasSet: true if successful)
grpcurl -plaintext -d '{"key":"lock:order:456","value":"d29ya2VyLTE=","ttl_seconds":30}' \
  localhost:50051 cachelib.grpc.CacheService/SetNX

# Response if lock acquired:
# {"wasSet": true, "message": "Key set successfully"}

# Response if lock already held:
# {"wasSet": false, "existingValue": "d29ya2VyLTE=", "message": "Key already exists"}

# Release lock
grpcurl -plaintext -d '{"key":"lock:order:456"}' \
  localhost:50051 cachelib.grpc.CacheService/Delete
```

### Rate Limiting (Increment)

```bash
# Increment counter (creates with value=1 if not exists)
grpcurl -plaintext -d '{"key":"rate:api:user:789","delta":1,"ttl_seconds":60}' \
  localhost:50051 cachelib.grpc.CacheService/Increment

# Response: {"success":true,"newValue":"1"}

# Decrement
grpcurl -plaintext -d '{"key":"rate:api:user:789","delta":1}' \
  localhost:50051 cachelib.grpc.CacheService/Decrement

# Check if newValue > limit to reject request
# Example: limit = 100 requests per minute
```

### Session Management (Touch)

```bash
# Extend session TTL without fetching/modifying value
grpcurl -plaintext -d '{"key":"session:abc123","ttl_seconds":1800}' \
  localhost:50051 cachelib.grpc.CacheService/Touch

# Response: {"success":true,"message":"TTL updated"}
```

### TTL Monitoring (GetTTL)

```bash
# Check remaining TTL
# Returns: -1 = no expiry, -2 = not found, 0+ = seconds remaining
grpcurl -plaintext -d '{"key":"session:abc123"}' \
  localhost:50051 cachelib.grpc.CacheService/GetTTL

# Response: {"found":true,"ttlSeconds":"1799"}
```

### Atomic Compare-and-Swap

```bash
# Update only if current value matches expected
grpcurl -plaintext -d '{"key":"version","expected_value":"djE=","new_value":"djI=","ttl_seconds":0}' \
  localhost:50051 cachelib.grpc.CacheService/CompareAndSwap

# Response if successful: {"success":true,"actualValue":"djI="}
# Response if mismatch: {"success":false,"actualValue":"djM="}
```

### Batch Operations

```bash
# MultiGet
grpcurl -plaintext -d '{"keys":["user:1","user:2","user:3"]}' \
  localhost:50051 cachelib.grpc.CacheService/MultiGet

# MultiSet
grpcurl -plaintext -d '{"items":[{"key":"k1","value":"djE=","ttl_seconds":60},{"key":"k2","value":"djI=","ttl_seconds":60}]}' \
  localhost:50051 cachelib.grpc.CacheService/MultiSet
```

### Cache Statistics

```bash
grpcurl -plaintext localhost:50051 cachelib.grpc.CacheService/Stats
```

Response:
```json
{
  "totalSize": "1069547520",
  "usedSize": "4194304",
  "itemCount": "1000",
  "hitRate": 0.95,
  "getCount": "5000",
  "hitCount": "4750",
  "missCount": "250",
  "setCount": "1000",
  "deleteCount": "50",
  "evictionCount": "0",
  "uptimeSeconds": "3600",
  "version": "1.2.2"
}
```

## Configuration

### Command-Line Options

| Option | Default | Description |
|--------|---------|-------------|
| `--address` | `0.0.0.0` | Server bind address |
| `--port` | `50051` | Server port |
| `--cache_name` | `grpc-cachelib` | Cache instance name |
| `--cache_size` | `1073741824` (1GB) | DRAM cache size in bytes |
| `--enable_nvm` | `false` | Enable NVM/flash caching |
| `--nvm_path` | `/tmp/cachelib_nvm` | Path to NVM cache file |
| `--nvm_size` | `10737418240` (10GB) | NVM cache size in bytes |
| `--nvm_block_size` | `4096` | NVM block size |
| `--nvm_reader_threads` | `32` | NVM async reader threads |
| `--nvm_writer_threads` | `32` | NVM async writer threads |
| `--enable_io_uring` | `true` | Use io_uring for NVM I/O |
| `--lru_refresh_time` | `60` | LRU refresh time (seconds) |
| `--max_item_size` | `4194304` (4MB) | Maximum item size |
| `--log_level` | `INFO` | Log level (DBG,INFO,WARN,ERR) |

### Docker Compose

```yaml
version: '3.8'
services:
  cachelib:
    image: ghcr.io/celikgo/cachelib-grpc-server:1.2.2
    ports:
      - "50051:50051"
    command: ["--cache_size=2147483648"]
    healthcheck:
      test: ["CMD", "grpc_health_probe", "-addr=:50051"]
      interval: 30s
      timeout: 10s
      retries: 3
```

## Proto File

The protobuf definition is available at:
- Inside container: `/opt/cachelib/proto/cache.proto`
- GitHub: [standalone_server/proto/cache.proto](proto/cache.proto)

Extract from container:
```bash
docker cp cachelib:/opt/cachelib/proto/cache.proto .
```

## Client Libraries

### Java

```xml
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-netty-shaded</artifactId>
  <version>1.60.0</version>
</dependency>
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-protobuf</artifactId>
  <version>1.60.0</version>
</dependency>
<dependency>
  <groupId>io.grpc</groupId>
  <artifactId>grpc-stub</artifactId>
  <version>1.60.0</version>
</dependency>
```

```java
try (CacheLibClient client = new CacheLibClient("localhost", 50051)) {
    // Ping
    boolean alive = client.ping();

    // Set with TTL
    client.set("user:123", "{\"name\": \"John\"}", 3600);

    // Get
    Optional<String> value = client.getString("user:123");

    // SetNX for distributed locking
    SetNXResult lock = client.setNX("lock:resource", "owner-1", 30);
    if (lock.wasSet()) {
        // Lock acquired
    }

    // Increment for rate limiting
    IncrementResult counter = client.increment("rate:user:123", 1, 60);
    if (counter.getNewValue() > 100) {
        // Rate limit exceeded
    }

    // Stats
    CacheStats stats = client.getStats();
    System.out.println("Hit rate: " + stats.getHitRate());
}
```

See [Java Client Example](../examples/java-client) for a complete implementation.

### Python

```bash
pip install grpcio grpcio-tools

# Generate Python stubs
python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. cache.proto
```

```python
import grpc
import cache_pb2
import cache_pb2_grpc

channel = grpc.insecure_channel('localhost:50051')
stub = cache_pb2_grpc.CacheServiceStub(channel)

# Set
stub.Set(cache_pb2.SetRequest(key="mykey", value=b"myvalue", ttl_seconds=60))

# Get
response = stub.Get(cache_pb2.GetRequest(key="mykey"))
if response.found:
    print(response.value)

# SetNX (distributed lock)
response = stub.SetNX(cache_pb2.SetNXRequest(
    key="lock:resource", value=b"owner-1", ttl_seconds=30))
if response.was_set:
    print("Lock acquired!")

# Increment (rate limiting)
response = stub.Increment(cache_pb2.IncrementRequest(
    key="rate:user:123", delta=1, ttl_seconds=60))
print(f"Counter: {response.new_value}")
```

### Go

```bash
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

protoc --go_out=. --go-grpc_out=. cache.proto
```

```go
import (
    "context"
    "google.golang.org/grpc"
    pb "your/package/cache"
)

conn, _ := grpc.Dial("localhost:50051", grpc.WithInsecure())
client := pb.NewCacheServiceClient(conn)
ctx := context.Background()

// Set
client.Set(ctx, &pb.SetRequest{Key: "mykey", Value: []byte("myvalue"), TtlSeconds: 60})

// Get
resp, _ := client.Get(ctx, &pb.GetRequest{Key: "mykey"})
if resp.Found {
    fmt.Println(string(resp.Value))
}

// SetNX (distributed lock)
lockResp, _ := client.SetNX(ctx, &pb.SetNXRequest{
    Key: "lock:resource", Value: []byte("owner-1"), TtlSeconds: 30})
if lockResp.WasSet {
    fmt.Println("Lock acquired!")
}
```

### Node.js

```bash
npm install @grpc/grpc-js @grpc/proto-loader
```

```javascript
const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');

const packageDefinition = protoLoader.loadSync('cache.proto');
const cacheProto = grpc.loadPackageDefinition(packageDefinition).cachelib.grpc;

const client = new cacheProto.CacheService('localhost:50051', grpc.credentials.createInsecure());

// Set
client.Set({ key: 'mykey', value: Buffer.from('myvalue'), ttl_seconds: 60 }, (err, response) => {
  console.log(response.success);
});

// Get
client.Get({ key: 'mykey' }, (err, response) => {
  if (response.found) {
    console.log(response.value.toString());
  }
});

// Increment
client.Increment({ key: 'counter', delta: 1, ttl_seconds: 60 }, (err, response) => {
  console.log(`Counter: ${response.new_value}`);
});
```

## Hybrid Caching (DRAM + NVM)

CacheLib supports transparent hybrid caching where hot items stay in DRAM and warm items are stored on flash/SSD.

```bash
docker run -d -p 50051:50051 \
  -v /mnt/nvme:/data/nvm \
  ghcr.io/celikgo/cachelib-grpc-server:1.2.2 \
  --cache_size=8589934592 \
  --enable_nvm=true \
  --nvm_path=/data/nvm/cache.dat \
  --nvm_size=107374182400 \
  --nvm_reader_threads=64 \
  --nvm_writer_threads=64
```

### How It Works

1. All writes go to DRAM first
2. When DRAM is full, evicted items are written to NVM based on admission policy
3. Gets check DRAM first, then NVM (with async promotion back to DRAM)
4. Navy engine handles SSD optimization (BigHash for small items, BlockCache for large)

## Performance

Benchmark results (1000 iterations, single node):

| Value Size | vs Redis |
|------------|----------|
| 100 bytes | -35% |
| 1 KB | -25% |
| 10 KB | -18% |

CacheLib's strength is at scale with hybrid RAM+SSD caching, not single-node raw speed. For large datasets that exceed RAM, CacheLib provides significant cost savings by using SSD as a cache tier.

## Building from Source

```bash
# 1. Build CacheLib dependencies
cd CacheLib
./contrib/build.sh -j -d deps

# 2. Build CacheLib
./contrib/build.sh -j -d cachelib

# 3. Install gRPC (v1.60.0 recommended)
# Follow https://grpc.io/docs/languages/cpp/quickstart/

# 4. Build the gRPC server
cd standalone_server
mkdir build && cd build
cmake -DCMAKE_PREFIX_PATH="$PWD/../../opt/cachelib" \
      -DCMAKE_BUILD_TYPE=Release ..
make -j

# 5. Run the server
./cachelib-grpc-server --port=50051 --cache_size=1073741824
```

### Build Docker Image

```bash
docker build -t cachelib-grpc-server -f standalone_server/Dockerfile .
```

## Docker Image

| Tag | Description |
|-----|-------------|
| `latest` | Latest stable release |
| `1.2.2` | Current version with all bug fixes |

**Registry:** `ghcr.io/celikgo/cachelib-grpc-server`

## Changelog

### v1.2.2
- Fixed `setCount` and `getCount` stats visibility with sequential consistency
- All atomic counters now use `memory_order_seq_cst` for guaranteed cross-thread visibility

### v1.2.0
- Added Redis-parity features: SetNX, Increment, Decrement, GetTTL, Touch, CompareAndSwap
- Synced Java client with server proto
- Fixed `uptimeSeconds` reporting
- Fixed `usedSizeBytes` calculation

### v1.1.0
- Fixed `freeMemoryBytes` API call
- Improved stats reporting

## Troubleshooting

### Common Issues

**Server won't start - "Failed to initialize cache manager"**
- Check that cache_size is sufficient (minimum ~50MB)
- Ensure NVM path is writable if NVM is enabled

**High latency on gets**
- Check if items are being fetched from NVM (slower than DRAM)
- Increase DRAM cache size for better hit rates
- Monitor eviction rate in stats

**"Connection refused" errors**
- Verify the server is running: `docker logs cachelib`
- Check firewall rules and port mappings
- Ensure correct host/port in client configuration

### Logging

Increase log verbosity for debugging:

```bash
docker run -d -p 50051:50051 ghcr.io/celikgo/cachelib-grpc-server:1.2.2 --log_level=DBG
```

## License

Apache License 2.0 - See [LICENSE](../LICENSE) for details.

## Support

- **Issues:** https://github.com/celikgo/CacheLib/issues
- **CacheLib Docs:** https://cachelib.org/
