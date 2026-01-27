# Spring Boot + CacheLib Demo

A complete example demonstrating how to use **CacheLib** as a high-performance cache backend in a **Spring Boot** application. This is similar to using Redis, but with CacheLib's superior performance and hybrid DRAM+SSD caching capabilities.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Architecture](#architecture)
- [API Reference](#api-reference)
- [Configuration](#configuration)
- [Development](#development)
- [Comparison with Redis](#comparison-with-redis)
- [Troubleshooting](#troubleshooting)

## Overview

This demo shows:
- Running CacheLib as a standalone gRPC server in Docker
- Connecting a Spring Boot application to CacheLib
- Implementing cache-aside pattern (check cache → miss → fetch from DB → cache)
- Cache invalidation on updates/deletes
- Direct cache operations via REST API
- Monitoring cache statistics

## Prerequisites

- Docker and Docker Compose
- Java 17+ (for local development only)
- Maven 3.8+ (for local development only)

## Quick Start

### 1. Clone and Navigate

```bash
cd examples/spring-cachelib-demo
```

### 2. Start Everything

```bash
docker-compose up -d
```

This starts:
- **cachelib** - CacheLib gRPC server on port 50051
- **spring-app** - Spring Boot application on port 8080

### 3. Verify Services

```bash
# Check both containers are healthy
docker-compose ps

# Check CacheLib logs
docker-compose logs cachelib

# Check Spring app logs
docker-compose logs spring-app
```

### 4. Test the API

```bash
# Get all users (from database)
curl http://localhost:8080/api/users

# Get a specific user (1st call = cache miss, 2nd call = cache hit)
curl http://localhost:8080/api/users/1
curl http://localhost:8080/api/users/1

# Check cache statistics - notice hitCount increased!
curl http://localhost:8080/api/cache/stats
```

### 5. Stop Services

```bash
docker-compose down
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Docker Network                          │
│                                                              │
│  ┌──────────────────┐         ┌──────────────────────────┐  │
│  │   Spring Boot    │  gRPC   │       CacheLib           │  │
│  │   Application    │────────▶│    gRPC Server           │  │
│  │                  │  :50051 │                          │  │
│  │  ┌────────────┐  │         │  ┌────────────────────┐  │  │
│  │  │ Controller │  │         │  │   DRAM Cache       │  │  │
│  │  └─────┬──────┘  │         │  │   (1 GB default)   │  │  │
│  │        │         │         │  └────────────────────┘  │  │
│  │  ┌─────▼──────┐  │         │           │              │  │
│  │  │  Service   │  │         │  ┌────────▼───────────┐  │  │
│  │  │  (Cache    │  │         │  │   Optional NVM     │  │  │
│  │  │   Logic)   │  │         │  │   (SSD Cache)      │  │  │
│  │  └─────┬──────┘  │         │  └────────────────────┘  │  │
│  │        │         │         │                          │  │
│  │  ┌─────▼──────┐  │         └──────────────────────────┘  │
│  │  │  CacheLib  │  │                                       │
│  │  │  Client    │  │                                       │
│  │  └────────────┘  │                                       │
│  │                  │                                       │
│  │    :8080         │                                       │
│  └──────────────────┘                                       │
│          │                                                   │
└──────────┼───────────────────────────────────────────────────┘
           │
    ┌──────▼──────┐
    │   Client    │
    │  (Browser,  │
    │   curl)     │
    └─────────────┘
```

### Data Flow

1. **Cache Hit**: Client → Spring App → CacheLib → Return cached data
2. **Cache Miss**: Client → Spring App → CacheLib (miss) → Database → CacheLib (store) → Return data

## API Reference

### User API (`/api/users`)

| Method | Endpoint | Description | Cache Behavior |
|--------|----------|-------------|----------------|
| GET | `/api/users` | List all users | No caching (always fresh) |
| GET | `/api/users/{id}` | Get user by ID | Cache-aside pattern |
| POST | `/api/users` | Create new user | No cache write |
| PUT | `/api/users/{id}` | Update user | Invalidates cache |
| DELETE | `/api/users/{id}` | Delete user | Invalidates cache |

**Examples:**

```bash
# List all users
curl http://localhost:8080/api/users

# Get single user (demonstrates caching)
curl http://localhost:8080/api/users/1

# Create user
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"email": "new@example.com", "name": "New User"}'

# Update user (invalidates cache)
curl -X PUT http://localhost:8080/api/users/1 \
  -H "Content-Type: application/json" \
  -d '{"email": "updated@example.com", "name": "Updated Name"}'

# Delete user (invalidates cache)
curl -X DELETE http://localhost:8080/api/users/1
```

### Cache API (`/api/cache`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/cache/health` | Check CacheLib connection |
| GET | `/api/cache/stats` | Get cache statistics |
| POST | `/api/cache/set` | Store a key-value pair |
| GET | `/api/cache/get` | Retrieve a value |
| DELETE | `/api/cache/delete` | Remove a key |

**Examples:**

```bash
# Check cache health
curl http://localhost:8080/api/cache/health

# Get cache statistics
curl http://localhost:8080/api/cache/stats

# Store a value (with 60 second TTL)
curl -X POST "http://localhost:8080/api/cache/set?key=mykey&value=myvalue&ttl=60"

# Retrieve a value
curl "http://localhost:8080/api/cache/get?key=mykey"

# Delete a key
curl -X DELETE "http://localhost:8080/api/cache/delete?key=mykey"
```

### Cache Statistics Response

```json
{
  "totalSizeBytes": 1069547520,
  "usedSizeBytes": 1069547520,
  "itemCount": 5,
  "hitRate": "80.00%",
  "getCount": 10,
  "hitCount": 8,
  "missCount": 2,
  "setCount": 5,
  "evictionCount": 0,
  "uptimeSeconds": 3600
}
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CACHELIB_HOST` | `localhost` | CacheLib server hostname |
| `CACHELIB_PORT` | `50051` | CacheLib server port |

### Spring Configuration (`application.yml`)

```yaml
cachelib:
  host: ${CACHELIB_HOST:localhost}
  port: ${CACHELIB_PORT:50051}
```

### CacheLib Server Options

The CacheLib server accepts these command-line arguments:

| Argument | Default | Description |
|----------|---------|-------------|
| `--address` | `0.0.0.0` | Listen address |
| `--port` | `50051` | Listen port |
| `--cache_size` | `1073741824` | Cache size in bytes (1 GB) |
| `--cache_name` | `grpc-cachelib` | Cache instance name |
| `--enable_nvm` | `false` | Enable NVM/SSD cache |
| `--nvm_path` | `/data/nvm/cache` | NVM cache file path |
| `--nvm_size` | `10737418240` | NVM cache size (10 GB) |

### Docker Compose Configuration

```yaml
services:
  cachelib:
    image: cachelib-grpc-server:latest
    command:
      - "--address=0.0.0.0"
      - "--port=50051"
      - "--cache_size=2147483648"  # 2 GB
    ports:
      - "50051:50051"
```

### Enable NVM (SSD) Cache

For larger datasets, enable hybrid DRAM+SSD caching:

```yaml
services:
  cachelib:
    image: cachelib-grpc-server:latest
    command:
      - "--address=0.0.0.0"
      - "--port=50051"
      - "--cache_size=1073741824"      # 1 GB DRAM
      - "--enable_nvm=true"
      - "--nvm_path=/data/nvm/cache"
      - "--nvm_size=10737418240"       # 10 GB SSD
    volumes:
      - nvm-data:/data/nvm

volumes:
  nvm-data:
```

## Development

### Project Structure

```
spring-cachelib-demo/
├── src/main/java/com/example/demo/
│   ├── DemoApplication.java           # Spring Boot entry point
│   ├── client/
│   │   └── CacheLibClient.java        # gRPC client wrapper
│   ├── config/
│   │   └── CacheLibConfig.java        # Spring bean configuration
│   ├── controller/
│   │   ├── UserController.java        # User REST API
│   │   └── CacheController.java       # Cache management API
│   └── service/
│       └── UserService.java           # Business logic with caching
├── src/main/proto/
│   └── cache.proto                    # gRPC protocol definition
├── src/main/resources/
│   └── application.yml                # Spring configuration
├── Dockerfile                         # Spring app container
├── docker-compose.yml                 # Full stack deployment
├── pom.xml                            # Maven dependencies
└── README.md                          # This file
```

### Build Locally

```bash
# Compile and run tests
mvn clean verify

# Run locally (requires CacheLib server running)
mvn spring-boot:run

# Build Docker image only
docker build -t spring-cachelib-demo .
```

### Run Without Docker Compose

```bash
# Start CacheLib server
docker run -d --name cachelib -p 50051:50051 cachelib-grpc-server:latest

# Run Spring app locally
mvn spring-boot:run
```

### Implementing Cache in Your Service

```java
@Service
public class MyService {

    private final CacheLibClient cache;
    private static final int CACHE_TTL = 300; // 5 minutes

    public MyService(CacheLibClient cache) {
        this.cache = cache;
    }

    public MyEntity getById(Long id) {
        String cacheKey = "entity:" + id;

        // Try cache first
        Optional<String> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return deserialize(cached.get());
        }

        // Cache miss - fetch from database
        MyEntity entity = repository.findById(id);

        // Store in cache
        cache.set(cacheKey, serialize(entity), CACHE_TTL);

        return entity;
    }

    public void update(Long id, MyEntity entity) {
        repository.save(entity);

        // Invalidate cache
        cache.delete("entity:" + id);
    }
}
```

## Comparison with Redis

| Aspect | Redis | CacheLib |
|--------|-------|----------|
| **Protocol** | RESP (text-based) | gRPC (binary, efficient) |
| **Client Setup** | `spring-boot-starter-data-redis` | gRPC client (included) |
| **Hybrid Cache** | No (memory only) | Yes (DRAM + SSD) |
| **Large Datasets** | Limited by RAM | Can use SSD for overflow |
| **Performance** | Very fast | Faster (optimized for caching) |
| **Clustering** | Built-in | Single node (use multiple instances) |
| **Persistence** | RDB/AOF | Optional warm restart |
| **Spring Integration** | Native `@Cacheable` | Manual (shown in this demo) |

### When to Use CacheLib

- Large datasets that exceed available RAM
- Need hybrid DRAM+SSD caching
- High-performance caching with predictable latency
- Applications already using Meta/Facebook infrastructure

### When to Use Redis

- Need pub/sub messaging
- Need built-in clustering
- Want native Spring `@Cacheable` support
- Smaller datasets that fit in RAM

## Troubleshooting

### Container won't start

```bash
# Check logs
docker-compose logs cachelib
docker-compose logs spring-app

# Restart
docker-compose down
docker-compose up -d
```

### Connection refused

```bash
# Verify CacheLib is running
docker-compose ps

# Test gRPC port
nc -zv localhost 50051
```

### Cache not working

```bash
# Check cache stats
curl http://localhost:8080/api/cache/stats

# Check Spring app logs for cache hits/misses
docker-compose logs -f spring-app
```

### High memory usage

Reduce cache size in `docker-compose.yml`:

```yaml
command:
  - "--cache_size=536870912"  # 512 MB instead of 1 GB
```

### Clear all data

```bash
# Restart containers (clears in-memory cache)
docker-compose restart
```

## License

Apache License 2.0

## Links

- [CacheLib GitHub](https://github.com/facebook/CacheLib)
- [CacheLib Documentation](https://cachelib.org/)
- [gRPC Java](https://grpc.io/docs/languages/java/)
- [Spring Boot](https://spring.io/projects/spring-boot)
