# CacheLib gRPC Server - Docker Usage Guide

A high-performance caching server from Meta/Facebook, available as a Docker container. Use it as an alternative to Redis with superior performance and hybrid DRAM+SSD caching capabilities.

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

## Proto File (Required for Clients)

All clients need the protocol buffer definition to generate gRPC stubs. Save as `cache.proto`:

```protobuf
syntax = "proto3";
package cachelib.grpc;
option java_package = "com.facebook.cachelib.grpc";

service CacheService {
  rpc Get(GetRequest) returns (GetResponse);
  rpc Set(SetRequest) returns (SetResponse);
  rpc Delete(DeleteRequest) returns (DeleteResponse);
  rpc Exists(ExistsRequest) returns (ExistsResponse);
  rpc MultiGet(MultiGetRequest) returns (MultiGetResponse);
  rpc Ping(PingRequest) returns (PingResponse);
  rpc Stats(StatsRequest) returns (StatsResponse);
}

message GetRequest { string key = 1; }
message GetResponse { bool found = 1; bytes value = 2; }

message SetRequest { string key = 1; bytes value = 2; int32 ttl_seconds = 3; }
message SetResponse { bool success = 1; }

message DeleteRequest { string key = 1; }
message DeleteResponse { bool success = 1; }

message ExistsRequest { string key = 1; }
message ExistsResponse { bool exists = 1; }

message MultiGetRequest { repeated string keys = 1; }
message MultiGetResponse { repeated KeyValue results = 1; }
message KeyValue { string key = 1; bytes value = 2; bool found = 3; }

message PingRequest {}
message PingResponse { string message = 1; }

message StatsRequest {}
message StatsResponse {
  uint64 total_size_bytes = 1;
  uint64 used_size_bytes = 2;
  uint64 item_count = 3;
  uint64 get_count = 4;
  uint64 hit_count = 5;
  uint64 miss_count = 6;
  uint64 set_count = 7;
  uint64 eviction_count = 8;
  uint64 uptime_seconds = 9;
}
```

## Client Examples

### Python

**Install dependencies:**
```bash
pip install grpcio grpcio-tools
python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. cache.proto
```

**Usage:**
```python
import grpc
from cache_pb2 import SetRequest, GetRequest, PingRequest, DeleteRequest, StatsRequest
from cache_pb2_grpc import CacheServiceStub

# Connect
channel = grpc.insecure_channel('localhost:50051')
cache = CacheServiceStub(channel)

# Ping - check server is alive
response = cache.Ping(PingRequest())
print(response.message)  # "pong"

# Set - store with 5 minute TTL
cache.Set(SetRequest(key="user:1", value=b'{"name":"John","email":"john@example.com"}', ttl_seconds=300))

# Get - retrieve value
response = cache.Get(GetRequest(key="user:1"))
if response.found:
    print(response.value.decode())  # {"name":"John","email":"john@example.com"}

# Delete - remove key
cache.Delete(DeleteRequest(key="user:1"))

# Stats - get cache statistics
stats = cache.Stats(StatsRequest())
print(f"Items: {stats.item_count}, Hit rate: {stats.hit_count}/{stats.get_count}")
```

### Go

**Install dependencies:**
```bash
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
protoc --go_out=. --go-grpc_out=. cache.proto
```

**Usage:**
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
    // Connect
    conn, err := grpc.Dial("localhost:50051", grpc.WithTransportCredentials(insecure.NewCredentials()))
    if err != nil {
        log.Fatal(err)
    }
    defer conn.Close()

    client := pb.NewCacheServiceClient(conn)
    ctx := context.Background()

    // Ping
    pong, _ := client.Ping(ctx, &pb.PingRequest{})
    log.Println("Ping:", pong.Message)

    // Set
    client.Set(ctx, &pb.SetRequest{
        Key:        "user:1",
        Value:      []byte(`{"name":"John"}`),
        TtlSeconds: 300,
    })

    // Get
    resp, _ := client.Get(ctx, &pb.GetRequest{Key: "user:1"})
    if resp.Found {
        log.Println("Value:", string(resp.Value))
    }

    // Delete
    client.Delete(ctx, &pb.DeleteRequest{Key: "user:1"})

    // Stats
    stats, _ := client.Stats(ctx, &pb.StatsRequest{})
    log.Printf("Items: %d, Hits: %d, Misses: %d\n", stats.ItemCount, stats.HitCount, stats.MissCount)
}
```

### Java / Spring Boot

**Maven dependencies (pom.xml):**
```xml
<dependencies>
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
    <dependency>
        <groupId>javax.annotation</groupId>
        <artifactId>javax.annotation-api</artifactId>
        <version>1.3.2</version>
    </dependency>
</dependencies>

<build>
    <extensions>
        <extension>
            <groupId>kr.motd.maven</groupId>
            <artifactId>os-maven-plugin</artifactId>
            <version>1.7.1</version>
        </extension>
    </extensions>
    <plugins>
        <plugin>
            <groupId>org.xolstice.maven.plugins</groupId>
            <artifactId>protobuf-maven-plugin</artifactId>
            <version>0.6.1</version>
            <configuration>
                <protocArtifact>com.google.protobuf:protoc:3.25.1:exe:${os.detected.classifier}</protocArtifact>
                <pluginId>grpc-java</pluginId>
                <pluginArtifact>io.grpc:protoc-gen-grpc-java:1.60.0:exe:${os.detected.classifier}</pluginArtifact>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>compile</goal>
                        <goal>compile-custom</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

**Usage:**
```java
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import com.google.protobuf.ByteString;
import com.facebook.cachelib.grpc.*;

public class CacheClient {
    private final ManagedChannel channel;
    private final CacheServiceGrpc.CacheServiceBlockingStub stub;

    public CacheClient(String host, int port) {
        this.channel = ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .build();
        this.stub = CacheServiceGrpc.newBlockingStub(channel);
    }

    public boolean ping() {
        PingResponse resp = stub.ping(PingRequest.newBuilder().build());
        return "pong".equals(resp.getMessage());
    }

    public void set(String key, String value, int ttlSeconds) {
        stub.set(SetRequest.newBuilder()
            .setKey(key)
            .setValue(ByteString.copyFromUtf8(value))
            .setTtlSeconds(ttlSeconds)
            .build());
    }

    public String get(String key) {
        GetResponse resp = stub.get(GetRequest.newBuilder().setKey(key).build());
        return resp.getFound() ? resp.getValue().toStringUtf8() : null;
    }

    public void delete(String key) {
        stub.delete(DeleteRequest.newBuilder().setKey(key).build());
    }

    public void shutdown() {
        channel.shutdown();
    }
}

// Usage
CacheClient cache = new CacheClient("localhost", 50051);
cache.set("user:1", "{\"name\":\"John\"}", 300);
String value = cache.get("user:1");
cache.delete("user:1");
cache.shutdown();
```

### Node.js

**Install dependencies:**
```bash
npm install @grpc/grpc-js @grpc/proto-loader
```

**Usage:**
```javascript
const grpc = require('@grpc/grpc-js');
const protoLoader = require('@grpc/proto-loader');

// Load proto
const packageDef = protoLoader.loadSync('cache.proto', {
  keepCase: true,
  longs: String,
  enums: String,
  defaults: true,
  oneofs: true
});
const proto = grpc.loadPackageDefinition(packageDef).cachelib.grpc;

// Connect
const client = new proto.CacheService(
  'localhost:50051',
  grpc.credentials.createInsecure()
);

// Ping
client.Ping({}, (err, response) => {
  console.log('Ping:', response.message);  // "pong"
});

// Set
client.Set({
  key: 'user:1',
  value: Buffer.from('{"name":"John"}'),
  ttl_seconds: 300
}, (err, response) => {
  console.log('Set success:', response.success);
});

// Get
client.Get({ key: 'user:1' }, (err, response) => {
  if (response.found) {
    console.log('Value:', response.value.toString());
  }
});

// Delete
client.Delete({ key: 'user:1' }, (err, response) => {
  console.log('Delete success:', response.success);
});

// Stats
client.Stats({}, (err, response) => {
  console.log('Items:', response.item_count);
  console.log('Hit rate:', response.hit_count + '/' + response.get_count);
});
```

### Rust

**Cargo.toml:**
```toml
[dependencies]
tonic = "0.10"
prost = "0.12"
tokio = { version = "1", features = ["full"] }

[build-dependencies]
tonic-build = "0.10"
```

**build.rs:**
```rust
fn main() {
    tonic_build::compile_protos("cache.proto").unwrap();
}
```

**Usage:**
```rust
use tonic::transport::Channel;

pub mod cache {
    tonic::include_proto!("cachelib.grpc");
}

use cache::cache_service_client::CacheServiceClient;
use cache::{GetRequest, SetRequest, PingRequest};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut client = CacheServiceClient::connect("http://localhost:50051").await?;

    // Ping
    let response = client.ping(PingRequest {}).await?;
    println!("Ping: {}", response.into_inner().message);

    // Set
    client.set(SetRequest {
        key: "user:1".into(),
        value: b"{\"name\":\"John\"}".to_vec(),
        ttl_seconds: 300,
    }).await?;

    // Get
    let response = client.get(GetRequest { key: "user:1".into() }).await?;
    let resp = response.into_inner();
    if resp.found {
        println!("Value: {}", String::from_utf8_lossy(&resp.value));
    }

    Ok(())
}
```

## Full Example Projects

### Spring Boot + CacheLib Demo

A complete Spring Boot application with Docker Compose deployment:

https://github.com/celikgo/CacheLib/tree/main/examples/spring-cachelib-demo

Features:
- Cache-aside pattern implementation
- REST API with automatic caching
- Cache statistics and health endpoints
- Docker Compose for full stack deployment

## Comparison with Redis

| Aspect | Redis | CacheLib |
|--------|-------|----------|
| **Protocol** | RESP (text-based) | gRPC (binary, efficient) |
| **Hybrid Cache** | No (memory only) | Yes (DRAM + SSD) |
| **Large Datasets** | Limited by RAM | Can use SSD for overflow |
| **Performance** | Very fast | Faster (optimized for caching) |
| **Clustering** | Built-in | Single node |
| **Spring Integration** | Native `@Cacheable` | Manual gRPC client |

### When to Use CacheLib

- Large datasets that exceed available RAM
- Need hybrid DRAM+SSD caching
- High-performance caching with predictable latency
- Binary data caching (images, serialized objects)

### When to Use Redis

- Need pub/sub messaging
- Need built-in clustering
- Want native Spring `@Cacheable` support
- Need data structures (lists, sets, sorted sets)

## Troubleshooting

### Connection refused

```bash
# Check if container is running
docker ps | grep cachelib

# Check container logs
docker logs cachelib

# Test port
nc -zv localhost 50051
```

### High memory usage

Reduce cache size:
```bash
docker run -d --name cachelib -p 50051:50051 ghcr.io/celikgo/cachelib-grpc-server:latest \
  --cache_size=536870912  # 512 MB
```

### Container crashes

Check logs for errors:
```bash
docker logs cachelib
```

Common issues:
- Insufficient memory for requested cache size
- Port already in use

## Links

- [CacheLib Documentation](https://cachelib.org/)
- [CacheLib GitHub](https://github.com/facebook/CacheLib)
- [gRPC Documentation](https://grpc.io/docs/)
- [Protocol Buffers](https://protobuf.dev/)

## License

Apache License 2.0
