# CacheLib gRPC Server - Release Notes

## v1.5.0 (2026-03-28)

### Upstream Sync

Merged **185 commits** from [facebook/CacheLib](https://github.com/facebook/CacheLib) upstream. Major highlights:

#### New Features (from upstream)
- **FixedSizeIndex**: Complete combined entry indexing system for the Navy SSD engine — CombinedEntryBlock, CombinedEntryManager, per-stream active CEB read/write
- **Access time tracking**: New map to track last accessed timestamps for items, threaded through Navy lookup/read/write paths
- **FlashCacheComponent**: New cache component with consistent hashing variant (`ConsistentFlashCacheComponent`)
- **Custom reinsertion policy**: Cachebench now supports custom reinsertion policies
- **Generic stats collection**: New `CacheComponent` stats interface with per-component counters
- **Pre/post queue callbacks**: New hook points for queue processing

#### Bug Fixes (from upstream)
- **Mutex starvation fix**: `RegionManager::getCleanRegion` could starve under contention
- **Data corruption fix**: Thread-local misuse across `co_await` boundaries
- **Memory monitor fix**: Excessive advising/reclaiming loop
- **Destructor race fix**: Race condition in item destructor callbacks
- **File size calculation**: Fixed for multi-file NVM caches
- **ObjectCache alignment**: Safe atomics for `updateObjectSize`
- **CompressedPtrTest.Stats**: Fixed failing in opt builds
- **Invalid key exceptions**: Now mention root cause in error message

#### Build & Infrastructure (from upstream)
- io_uring migrated from `folly/experimental/io` to `folly/io/async`
- Updated to googletest v1.17.0
- Ubuntu 24.04 as default GitHub Actions runner
- Static lib handling for glog, gflags, c-ares, libevent, libaio
- xxhash via cmake instead of manual find
- Removed gperf dependency

### Docker Improvements
- **magic_enum via cmake**: Proper `find_package(magic_enum CONFIG)` support — no more header-only workaround
- **Updated CMake patches**: Exception tracer libraries use new upstream names (`Folly::folly_debugging_exception_tracer_*`)
- **Optional dependency handling**: `FBThrift::thrift_dynamic_value` and `magic_enum::magic_enum` checked conditionally for Docker compatibility

### Upgrade

```bash
docker pull ghcr.io/celikgo/cachelib-grpc-server:1.5.0
```

All changes are backward-compatible. Existing clients work without modifications.

### Docker

```bash
# Pull
docker pull ghcr.io/celikgo/cachelib-grpc-server:1.5.0

# Run
docker run -d -p 50051:50051 -p 9090:9090 \
  ghcr.io/celikgo/cachelib-grpc-server:1.5.0 \
  --cache_size=2147483648

# Verify
docker run --rm ghcr.io/celikgo/cachelib-grpc-server:1.5.0 --version
# cachelib-grpc-server 1.5.0

# Prometheus metrics
curl http://localhost:9090/metrics
```

---

## v1.4.0 (2026-03-24)

### Upgrade

```bash
docker pull celikgo/cachelib-grpc-server:1.4.0
```

All proto changes are **backward-compatible** — existing clients work without modifications.
Regenerate your gRPC stubs from the updated `cache.proto` to access new fields.

---

### 1. `size_bytes` in SetResponse

Set operations now return the stored value size in bytes. No client changes needed —
the field is automatically populated on success.

**Proto diff:**
```protobuf
message SetResponse {
  bool success = 1;
  string message = 2;
  int64 size_bytes = 3;  // NEW — actual stored value size
}
```

**Usage (grpcurl):**
```bash
$ grpcurl -plaintext -d '{"key":"user:123","value":"eyJuYW1lIjoiYWxpY2UifQ==","ttl_seconds":300}' \
    localhost:50051 cachelib.grpc.CacheService/Set

{
  "success": true,
  "message": "OK",
  "size_bytes": "17"
}
```

**Usage (Java):**
```java
SetResponse resp = stub.set(SetRequest.newBuilder()
    .setKey("user:123")
    .setValue(ByteString.copyFromUtf8(json))
    .setTtlSeconds(300)
    .build());
long storedBytes = resp.getSizeBytes();  // 17
```

**Use case:** Track stored sizes for monitoring dashboards without computing
`value.length()` client-side. Useful when compression or encoding makes the stored
size different from what the client sent.

---

### 2. Enriched Scan with per-key metadata (`include_details`)

Scan now supports `include_details=true` to return TTL and size for each matched key.
Without this flag, behavior is unchanged (only key names returned).

**Proto diff:**
```protobuf
message ScanRequest {
  string pattern = 1;
  string cursor = 2;
  int32 count = 3;
  bool include_details = 4;  // NEW — opt-in for per-key metadata
}

// NEW message
message KeyInfo {
  string key = 1;
  int64 ttl_remaining = 2;  // seconds, -1 = no expiry
  int64 size_bytes = 3;
}

message ScanResponse {
  repeated string keys = 1;
  string next_cursor = 2;
  bool has_more = 3;
  repeated KeyInfo key_details = 4;  // NEW — populated when include_details=true
}
```

**Usage (grpcurl):**
```bash
# Without details (backward-compatible, same as before)
$ grpcurl -plaintext -d '{"pattern":"market:*"}' \
    localhost:50051 cachelib.grpc.CacheService/Scan

{
  "keys": ["market:AAPL", "market:GOOG"]
}

# With details — returns TTL and size per key
$ grpcurl -plaintext -d '{"pattern":"market:*","include_details":true}' \
    localhost:50051 cachelib.grpc.CacheService/Scan

{
  "keys": ["market:AAPL", "market:GOOG"],
  "key_details": [
    { "key": "market:AAPL", "ttl_remaining": "542", "size_bytes": "3" },
    { "key": "market:GOOG", "ttl_remaining": "542", "size_bytes": "3" }
  ]
}
```

**Usage (Python):**
```python
resp = stub.Scan(ScanRequest(pattern="market:*", include_details=True))
for info in resp.key_details:
    print(f"{info.key}: TTL={info.ttl_remaining}s, size={info.size_bytes}B")
    # market:AAPL: TTL=542s, size=3B
    # market:GOOG: TTL=542s, size=3B
```

**TTL values:**
| Value | Meaning |
|-------|---------|
| `-1` | Key has no expiration |
| `0` | Key is expired (should not appear in scan) |
| `N` | N seconds remaining until expiry |

**Use case:** Debugging cache miss rate alerts. When the Discovery Server Team hit
a 76% miss rate, they had no visibility into which keys existed or their TTLs.
`include_details=true` lets you inspect the cache state without fetching values.

---

### 3. Reminder: Features you already have

Based on Discovery Server Team feedback, many requested features already exist:

| You asked for | We already have | RPC name |
|---|---|---|
| MGet (batch get) | `MultiGet` | Returns value + `ttl_remaining` per key |
| MSet (batch set) | `MultiSet` | Returns `succeeded_count` / `failed_count` / `failed_keys` |
| Keys/Scan | `Scan` | Pattern matching with cursor pagination (now with `include_details`) |
| Touch/Expire | `Touch` | Updates TTL without re-fetching the value |
| Stats | `Stats` | Hit/miss/eviction/memory/NVM/uptime counters |

Full RPC list: `grpcurl -plaintext localhost:50051 list cachelib.grpc.CacheService`

---

### Build & Infrastructure

- Fixed aarch64 Docker linking (`-Wl,--copy-dt-needed-entries`) for libunwind/liblzma
- Added Docker `tester` stage (`docker build --target tester`) for CI test execution
- Fixed test namespace resolution and include paths for Docker builds

### Docker

```bash
# Pull
docker pull celikgo/cachelib-grpc-server:1.4.0

# Run
docker run -d -p 50051:50051 -p 9090:9090 \
  celikgo/cachelib-grpc-server:1.4.0 \
  --cache_size=2147483648

# Verify
docker run --rm celikgo/cachelib-grpc-server:1.4.0 --version
# cachelib-grpc-server 1.4.0

# Prometheus metrics
curl http://localhost:9090/metrics
```

---

## v1.3.1 (2026-02-10)

### Bug Fixes

- **Fix Flush RPC (P0)**: Flush was a stub that logged a warning and returned 0. Now uses CacheLib's `AccessIterator` to iterate all items and remove them by key, returning the actual removed count.
- **Fix Scan RPC (P0)**: Scan was a stub that returned empty results. Now implements cursor-based pagination using CacheLib's `AccessIterator` with glob pattern matching. Cursor = last returned key; empty cursor = start from beginning.

### Improvements

- **`--version` CLI flag**: `cachelib-grpc-server --version` prints version and exits immediately (handled before `folly::Init` for instant response)
- **Prometheus `cachelib_expired_total` counter**: Tracks items found expired during get operations (from `numCacheGetExpiries`)
- **Prometheus `cachelib_info` gauge**: Exposes server version as a label (`cachelib_info{version="1.3.1"} 1`) for Grafana dashboards
- **Populate `expiredCount` in Stats RPC**: The `expired_count` field in Stats responses is now populated from CacheLib's `numCacheGetExpiries` global stat (was always 0)

### Docker

- Image: `cachelib-grpc-server:1.3.1`
- Test: `docker run --rm cachelib-grpc-server:1.3.1 --version` outputs `cachelib-grpc-server 1.3.1`

---

## v1.3.0 (2026-02-09)

### New Features

- **gRPC Server Reflection**: Service discovery via `grpcurl` without proto files
  - `grpcurl -plaintext localhost:50051 list` now works out of the box
- **MultiDelete RPC**: Batch delete multiple keys in a single RPC call
  - Returns deleted count and not-found count
- **Pipeline Streaming RPC**: Bidirectional streaming for batching mixed operations
  - Supports Get, Set, Delete, and Exists in a single stream
  - Each request/response carries a `sequence_id` for correlation
- **Prometheus Metrics Endpoint**: HTTP `/metrics` endpoint on port 9090
  - Exposes cache size, hit rate, operation counters, NVM stats, uptime
  - Compatible with Prometheus scraping and Grafana dashboards
  - Configurable via `--metrics_port` flag (0 to disable)
- **Container Health Probe**: `grpc_health_probe` binary bundled in Docker image
  - Enables native Kubernetes/Docker health checks
  - Docker HEALTHCHECK directive pre-configured

### Improvements

- **CAS TTL Convention Fix**: `CompareAndSwap` now uses `ttl_seconds=0` for no expiration, consistent with Set/SetNX/Increment/Decrement
  - New `keep_ttl` field preserves existing TTL when updating value
  - **Breaking change**: Previously `0 = keep existing TTL, -1 = no expiry`; now `0 = no expiry` (matching all other operations)

### Docker

- Prometheus metrics port (9090) exposed in Dockerfile
- `grpc_health_probe` binary included for container health checks
- HEALTHCHECK directive uses `grpc_health_probe` for reliable health monitoring

---

## v1.2.2 (2026-02-09)

### Multi-Architecture Support
- Docker images now available for both **linux/amd64** (x86_64) and **linux/arm64** (Apple Silicon, AWS Graviton)
- Resolves crash-loop issue when running arm64-only images on amd64 production servers

### Upstream Sync
- Merged 98 commits from [facebook/CacheLib](https://github.com/facebook/CacheLib) upstream

### Bug Fixes (from upstream)
- **Expired item destructor callback**: `destructorCb_` is now correctly called for expired items, preventing resource leaks on eviction
- **FixedSizeIndex key hash retrieval**: Fixed `onKeyHashRetrievalFromLocation()` returning incorrect results
- **Large key sampling**: Fixed boundary checks in item sampling path for large keys
- **Buffer underflow protection**: Added safety check for potential buffer underflow in navy
- **NVM_ADMIT logging**: Fixed logging and added size/usecaseID to NVM admission logger
- **OSS CI fixes**: Integration tests and build system improvements for open-source builds

### Performance Improvements (from upstream)
- **Stats vector presizing**: AC stats vector is now pre-allocated, avoiding runtime reallocation
- **Modern vector APIs**: Device.cpp updated to use modern C++ vector operations
- **Navy thread nice values**: Support for setting thread priorities on navy background threads

### CI/CD
- Added GitHub Actions workflow for automated multi-platform Docker builds
- Triggered on version tag push (`v*`) or manual dispatch
- Uses Docker layer caching (GHA cache) for faster rebuilds

### Documentation
- Updated DOCKER_USAGE.md to v1.2.2
- Added build instructions (multi-platform, native, CI/CD)
- Added changelog section
- Added architecture mismatch troubleshooting

---

## v1.2.1 (2025-09-21)

### Bug Fixes
- Fixed stats counter visibility using sequential consistency memory ordering
- Synced Java client with server v1.2.0 API changes
- Fixed `freeMemorySize` -> `freeMemoryBytes` API call mismatch

### Documentation
- Updated README with v1.2.1 documentation
- Updated DOCKER_USAGE.md version references

---

## v1.2.0 (2025-09-20)

### New Features - Redis Parity
- **SetNX**: Set-if-not-exists for distributed locking
- **Increment / Decrement**: Atomic counter operations with TTL support
- **CompareAndSwap**: Native atomic CAS (replaces Redis Lua scripts)
- **GetTTL**: Query remaining time-to-live for a key
- **Touch**: Update/extend TTL without modifying value
- **Scan**: Iterate keys with pattern matching and cursor-based pagination
- **Flush**: Clear all cache entries (with optional NVM flush)

### Batch Operations
- **MultiGet**: Retrieve multiple keys in a single RPC call
- **MultiSet**: Store multiple key-value pairs in a single RPC call

### Stats Improvements
- Added hit/miss/eviction/expired counters
- Added uptime tracking
- Added server version reporting
- Added NVM statistics (if enabled)

---

## v1.0.0 (2025-09-18)

### Initial Release
- Standalone gRPC server wrapping Facebook CacheLib
- Basic operations: Get, Set, Delete, Exists
- Hybrid DRAM + SSD (NVM) caching support
- Multi-stage Docker build for minimal runtime image
- Java client library with Spring Boot integration
- Proto file for multi-language client generation
- Health check endpoint (Ping)
- Cache statistics endpoint (Stats)
