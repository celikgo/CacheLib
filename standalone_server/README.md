# CacheLib gRPC Server

A standalone, Dockerized gRPC server that wraps Facebook's CacheLib library, providing a Redis-like cache service accessible from any language via gRPC.

## Overview

This project extends CacheLib to run as a standalone network service, similar to how Redis or Memcached work. Instead of embedding CacheLib directly in your application, you can deploy it as a container and connect from any language with gRPC support (Java, Python, Go, Node.js, etc.).

### Key Features

- **Redis-like interface**: Get, Set, Delete operations with optional TTL
- **High performance**: Built on CacheLib's battle-tested caching engine
- **Hybrid caching**: Supports DRAM + NVM (flash/SSD) caching
- **Production-ready**: Comprehensive logging, error handling, and health checks
- **Multi-language support**: Any language with gRPC support can be a client
- **Docker-native**: Easy deployment as a container

### Trade-offs

| Consideration | In-Process CacheLib | gRPC Server |
|---------------|---------------------|-------------|
| Latency | Sub-microsecond | ~100us-1ms (network) |
| Throughput | Millions ops/sec | 100K+ ops/sec |
| Language Support | C++ only | Any with gRPC |
| Deployment | Library dependency | Separate service |
| Scaling | Per-process | Horizontal |
| Memory Sharing | Direct access | Serialization required |

Use the gRPC server when:
- You need caching from non-C++ applications
- You want to share cache across multiple services
- You need independent cache service scaling
- You prefer operational simplicity over raw performance

## Quick Start

### Using Docker (Recommended)

```bash
# Build the image
cd CacheLib
docker build -f standalone_server/Dockerfile -t cachelib-grpc-server .

# Run with default settings (1GB DRAM cache)
docker run -d -p 50051:50051 --name cachelib-server cachelib-grpc-server

# Run with custom cache size (4GB)
docker run -d -p 50051:50051 cachelib-grpc-server \
  --cache_size=4294967296

# Run with hybrid caching (DRAM + SSD)
docker run -d -p 50051:50051 \
  -v /path/to/ssd:/data/nvm \
  cachelib-grpc-server \
  --cache_size=2147483648 \
  --enable_nvm=true \
  --nvm_path=/data/nvm/cache.dat \
  --nvm_size=10737418240
```

### Using Docker Compose

```bash
cd standalone_server
docker-compose up -d

# Or with NVM support
docker-compose --profile nvm up -d
```

### Building from Source

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
      -DCMAKE_BUILD_TYPE=Release \
      -DBUILD_TESTS=ON ..
make -j

# 5. Run the server
./cachelib-grpc-server --port=50051 --cache_size=1073741824
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

### Environment Variables

You can also configure via environment variables (useful in Docker):

```bash
CACHELIB_CACHE_SIZE=2147483648
CACHELIB_ENABLE_NVM=true
CACHELIB_NVM_SIZE=10737418240
```

## gRPC API

### Service Definition

```protobuf
service CacheService {
  rpc Get(GetRequest) returns (GetResponse) {}
  rpc Set(SetRequest) returns (SetResponse) {}
  rpc Delete(DeleteRequest) returns (DeleteResponse) {}
  rpc MultiGet(MultiGetRequest) returns (MultiGetResponse) {}
  rpc MultiSet(MultiSetRequest) returns (MultiSetResponse) {}
  rpc Exists(ExistsRequest) returns (ExistsResponse) {}
  rpc Stats(StatsRequest) returns (StatsResponse) {}
  rpc Ping(PingRequest) returns (PingResponse) {}
}
```

### Operations

#### Set

Stores a key-value pair with optional TTL.

```protobuf
message SetRequest {
  string key = 1;
  bytes value = 2;
  int64 ttl_seconds = 3;  // 0 = no expiration
}

message SetResponse {
  bool success = 1;
  string message = 2;
}
```

#### Get

Retrieves a value by key.

```protobuf
message GetRequest {
  string key = 1;
}

message GetResponse {
  bool found = 1;
  bytes value = 2;
  int64 ttl_remaining = 3;  // Remaining TTL in seconds
}
```

#### Delete

Removes a key from the cache.

```protobuf
message DeleteRequest {
  string key = 1;
}

message DeleteResponse {
  bool success = 1;
  bool key_existed = 2;
  string message = 3;
}
```

## Client Examples

### Java Client

A complete Java client is provided in `examples/java-client/`.

```java
try (CacheLibClient client = new CacheLibClient("localhost", 50051)) {
    // Ping
    boolean alive = client.ping();

    // Set with TTL
    client.set("user:123", "{\"name\": \"John\"}", 3600);

    // Get
    Optional<String> value = client.getString("user:123");

    // Delete
    boolean existed = client.delete("user:123");

    // Multi-get
    Map<String, Optional<byte[]>> results = client.multiGet(
        Arrays.asList("key1", "key2", "key3")
    );

    // Stats
    CacheStats stats = client.getStats();
    System.out.println("Hit rate: " + stats.getHitRate());
}
```

Build and run:

```bash
cd examples/java-client
mvn package
java -jar target/cachelib-grpc-client-1.0.0-SNAPSHOT.jar localhost 50051
```

### Python Client (Example)

```python
import grpc
import cache_pb2
import cache_pb2_grpc

channel = grpc.insecure_channel('localhost:50051')
stub = cache_pb2_grpc.CacheServiceStub(channel)

# Set
response = stub.Set(cache_pb2.SetRequest(
    key="mykey",
    value=b"myvalue",
    ttl_seconds=3600
))
print(f"Set success: {response.success}")

# Get
response = stub.Get(cache_pb2.GetRequest(key="mykey"))
if response.found:
    print(f"Value: {response.value.decode()}")
```

### Go Client (Example)

```go
conn, _ := grpc.Dial("localhost:50051", grpc.WithInsecure())
client := pb.NewCacheServiceClient(conn)

// Set
_, _ = client.Set(context.Background(), &pb.SetRequest{
    Key:        "mykey",
    Value:      []byte("myvalue"),
    TtlSeconds: 3600,
})

// Get
resp, _ := client.Get(context.Background(), &pb.GetRequest{Key: "mykey"})
if resp.Found {
    fmt.Printf("Value: %s\n", string(resp.Value))
}
```

## Hybrid Caching (DRAM + NVM)

CacheLib supports transparent hybrid caching where hot items stay in DRAM and warm items are stored on flash/SSD.

### Configuration for Hybrid Mode

```bash
docker run -d -p 50051:50051 \
  -v /mnt/nvme:/data/nvm \
  --device /dev/nvme0n1:/dev/nvme0n1 \
  cachelib-grpc-server \
  --cache_size=8589934592 \
  --enable_nvm=true \
  --nvm_path=/data/nvm/cache.dat \
  --nvm_size=107374182400 \
  --nvm_reader_threads=64 \
  --nvm_writer_threads=64 \
  --enable_io_uring=true
```

### How It Works

1. All writes go to DRAM first
2. When DRAM is full, evicted items are written to NVM based on admission policy
3. Gets check DRAM first, then NVM (with async promotion back to DRAM)
4. Navy engine handles SSD optimization (BigHash for small items, BlockCache for large)

## Monitoring

### Health Checks

The server exposes gRPC health checking at the standard endpoint.

```bash
# Using grpc_health_probe
grpc_health_probe -addr=localhost:50051
```

### Statistics

Use the `Stats` RPC to get runtime metrics:

```
total_size: 1073741824
used_size: 524288000
item_count: 100000
hit_rate: 0.95
get_count: 1000000
hit_count: 950000
miss_count: 50000
set_count: 100000
eviction_count: 10000
nvm_enabled: true
nvm_size: 10737418240
nvm_used: 5368709120
uptime_seconds: 3600
```

### Prometheus Metrics (Future)

Prometheus metrics endpoint is planned for a future release.

## Testing

### Run Unit Tests

```bash
cd standalone_server/build
cmake -DBUILD_TESTS=ON ..
make -j
./cache_manager_test
./cache_service_test
```

### Integration Testing

```bash
# Start the server
docker run -d -p 50051:50051 --name test-cache cachelib-grpc-server

# Run Java client demo
cd examples/java-client
mvn package exec:java

# Cleanup
docker stop test-cache && docker rm test-cache
```

## Performance Tuning

### Optimal Settings for High Throughput

```bash
# For a server with 64GB RAM, NVMe SSD
cachelib-grpc-server \
  --cache_size=32212254720 \
  --enable_nvm=true \
  --nvm_path=/mnt/nvme/cache.dat \
  --nvm_size=214748364800 \
  --nvm_reader_threads=64 \
  --nvm_writer_threads=64 \
  --lru_refresh_time=30 \
  --max_item_size=16777216
```

### Docker Resource Limits

```yaml
deploy:
  resources:
    limits:
      memory: 40G
    reservations:
      memory: 35G
```

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
- Verify the server is running: `docker logs cachelib-server`
- Check firewall rules and port mappings
- Ensure correct host/port in client configuration

### Logging

Increase log verbosity for debugging:

```bash
cachelib-grpc-server --log_level=DBG
```

## Architecture

```
                    ┌─────────────────────────────────────┐
                    │         gRPC Clients                │
                    │  (Java, Python, Go, Node.js, ...)   │
                    └──────────────┬──────────────────────┘
                                   │ gRPC (port 50051)
                    ┌──────────────▼──────────────────────┐
                    │        CacheServiceImpl             │
                    │     (gRPC Service Handler)          │
                    └──────────────┬──────────────────────┘
                                   │
                    ┌──────────────▼──────────────────────┐
                    │         CacheManager                │
                    │    (CacheLib Wrapper Layer)         │
                    └──────────────┬──────────────────────┘
                                   │
                    ┌──────────────▼──────────────────────┐
                    │       CacheLib LruAllocator         │
                    ├─────────────────────────────────────┤
                    │  ┌─────────────┐ ┌───────────────┐  │
                    │  │    DRAM     │ │  NVM (Navy)   │  │
                    │  │   Cache     │ │  Flash Cache  │  │
                    │  └─────────────┘ └───────────────┘  │
                    └─────────────────────────────────────┘
```

## Contributing

We welcome contributions! Please see the main CacheLib [CONTRIBUTING.md](../CONTRIBUTING.md) for guidelines.

### Development Setup

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is part of CacheLib and is licensed under the Apache 2.0 License. See [LICENSE](../LICENSE) for details.
