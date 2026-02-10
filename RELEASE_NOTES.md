# CacheLib gRPC Server - Release Notes

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
