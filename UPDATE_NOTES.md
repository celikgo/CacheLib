# CacheLib gRPC Server - Update Notes

## Updating to v1.6.0

### What Changed
Adds a dedicated `Incr` RPC for fixed-window rate-limit buckets.
Atomic increment with create-and-stamp-TTL on miss, no-TTL-extend on
hit. See `RELEASE_NOTES.md` for the full contract.

### Migration Steps

#### 1. Pull the new image
```bash
docker pull ghcr.io/celikgo/cachelib-grpc-server:1.6.0
```

#### 2. Update your Docker Compose / deployment
```yaml
services:
  cachelib:
    image: ghcr.io/celikgo/cachelib-grpc-server:1.6.0  # was :1.5.0
    ports:
      - "50051:50051"
      - "9090:9090"
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

#### 4. (Optional) Regenerate client stubs
Only needed if you want to call the new RPC. Existing stubs continue
to work for every other operation.

### Breaking Changes
None. The new RPC is additive; the existing `Increment` / `Decrement`
RPCs and every other operation are unchanged.

### Notes
- `Incr` and `Increment` coexist. Use `Incr` for rate-limit buckets
  where the window must be sealed at creation; use `Increment` for
  general counters where re-arming the TTL on each write is fine.
- Clients that have not yet regenerated stubs will get
  `UNIMPLEMENTED` from `Incr`. Wire up a fail-open path with a
  tagged counter so the gap is observable.

---

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

> A dedicated `Incr` RPC with fixed-window rate-limit semantics was
> added later in v1.6.0 — see the v1.6.0 section above.

---

## Updating to v1.0.0

Initial release. No migration needed.
