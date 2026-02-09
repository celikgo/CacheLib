# CacheLib gRPC Server - Update Notes

## Updating to v1.2.2

### What Changed
This release adds multi-architecture Docker support and syncs with the latest upstream facebook/CacheLib.

### Migration Steps

#### 1. Pull the new image
```bash
# The new image auto-selects the correct architecture
docker pull ghcr.io/celikgo/cachelib-grpc-server:1.2.2
```

#### 2. Update your Docker Compose / deployment
```yaml
services:
  cachelib:
    image: ghcr.io/celikgo/cachelib-grpc-server:1.2.2  # was :1.2.1
    ports:
      - "50051:50051"
    command:
      - "--address=0.0.0.0"
      - "--port=50051"
      - "--cache_size=1073741824"
    restart: unless-stopped
```

#### 3. Restart the service
```bash
docker compose pull
docker compose up -d
```

### Breaking Changes
None. v1.2.2 is fully backward-compatible with v1.2.1. The gRPC API, proto file, and all server options remain unchanged.

### Important Notes
- **amd64 servers**: The v1.2.1 image was arm64-only and would crash-loop on x86_64 machines. v1.2.2 fixes this with multi-arch support.
- **Cache data**: Upgrading requires a restart. In-memory cache contents will be lost. NVM (SSD) cache data is not preserved across versions.
- **Client compatibility**: No client changes needed. The proto file and API are identical to v1.2.1.

---

## Updating to v1.2.1

### What Changed
Bug fixes for stats counter visibility and Java client API sync.

### Migration Steps
```bash
docker pull ghcr.io/celikgo/cachelib-grpc-server:1.2.1
docker compose up -d
```

### Breaking Changes
None.

### Notes
- Java client users: update the client JAR to match the v1.2.0 API (`freeMemoryBytes` replaces `freeMemorySize`)

---

## Updating to v1.2.0

### What Changed
Major feature release adding Redis-parity operations.

### Migration Steps
```bash
docker pull ghcr.io/celikgo/cachelib-grpc-server:1.2.0
docker compose up -d
```

### Breaking Changes
None. All new operations are additive.

### New Proto Messages
If you use code generation, regenerate your client stubs from the updated `cache.proto`:
```bash
# Python
python -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. cache.proto

# Java (via Maven/Gradle protobuf plugin)
mvn generate-sources

# Go
protoc --go_out=. --go-grpc_out=. cache.proto
```

### New Operations Available
- `SetNX`, `Increment`, `Decrement`, `CompareAndSwap`
- `GetTTL`, `Touch`
- `MultiGet`, `MultiSet`
- `Scan`, `Flush`

---

## Updating to v1.0.0

Initial release. No migration needed.
