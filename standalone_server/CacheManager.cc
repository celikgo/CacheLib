// Copyright (c) Meta Platforms, Inc. and affiliates.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "CacheManager.h"

#include <cstring>

#include <cachelib/allocator/nvmcache/NavyConfig.h>
#include <folly/logging/xlog.h>

namespace cachelib {
namespace grpc_server {

CacheManager::CacheManager(const CacheConfig& config) : config_(config) {
  XLOG(INFO) << "CacheManager created with config:"
             << " cacheName=" << config_.cacheName
             << " cacheSize=" << config_.cacheSize
             << " enableNvm=" << config_.enableNvm;
}

CacheManager::~CacheManager() {
  shutdown();
}

bool CacheManager::initialize() {
  try {
    CacheAllocatorConfig cacheConfig;

    // Basic configuration
    cacheConfig.setCacheName(config_.cacheName)
        .setCacheSize(config_.cacheSize)
        .setAccessConfig({25, 10})  // Hash table config
        .validate();

    // Configure NVM if enabled
    if (config_.enableNvm && !config_.nvmCachePath.empty()) {
      configureNvmCache(cacheConfig);
    }

    // Create the cache
    cache_ = std::make_unique<Cache>(cacheConfig);

    // Add the default pool using all available memory
    defaultPoolId_ = cache_->addPool(
        config_.defaultPoolName,
        cache_->getCacheMemoryStats().ramCacheSize);

    startTime_ = std::chrono::steady_clock::now();

    XLOG(INFO) << "CacheManager initialized successfully"
               << " poolId=" << static_cast<int>(defaultPoolId_)
               << " poolSize=" << cache_->getCacheMemoryStats().ramCacheSize;

    return true;
  } catch (const std::exception& ex) {
    XLOG(ERR) << "Failed to initialize CacheManager: " << ex.what();
    return false;
  }
}

void CacheManager::configureNvmCache(CacheAllocatorConfig& cacheConfig) {
  XLOG(INFO) << "Configuring NVM cache: path=" << config_.nvmCachePath
             << " size=" << config_.nvmCacheSize;

  facebook::cachelib::navy::NavyConfig navyConfig;

  // Set up the NVM device
  navyConfig.setSimpleFile(
      config_.nvmCachePath,
      config_.nvmCacheSize,
      true /* truncateFile */);

  navyConfig.setBlockSize(config_.nvmBlockSize);
  navyConfig.setDeviceMaxWriteSize(1024 * 1024);  // 1MB max write

  // Configure async I/O
  navyConfig.enableAsyncIo(
      config_.nvmReaderThreads,
      config_.nvmWriterThreads,
      config_.enableIoUring,
      256 /* stackSizeKB */);

  // Configure BigHash for small objects (items < 2KB)
  auto& bigHashConfig = navyConfig.bigHash();
  bigHashConfig.setSizePctAndMaxItemSize(10, 2048);  // 10% of NVM for BigHash, max 2KB items
  bigHashConfig.setBucketSize(4096);
  bigHashConfig.setBucketBfSize(8);

  // Configure BlockCache for larger objects
  auto& blockCacheConfig = navyConfig.blockCache();
  blockCacheConfig.setRegionSize(16 * 1024 * 1024);  // 16MB regions
  blockCacheConfig.setDataChecksum(true);

  // Enable random admission policy
  navyConfig.enableRandomAdmPolicy().setAdmProbability(1.0);

  // Enable NVM cache in the allocator config
  typename Cache::NvmCacheConfig nvmCacheConfig;
  nvmCacheConfig.navyConfig = navyConfig;
  cacheConfig.enableNvmCache(nvmCacheConfig);

  XLOG(INFO) << "NVM cache configured successfully";
}

void CacheManager::shutdown() {
  if (cache_) {
    XLOG(INFO) << "Shutting down CacheManager...";
    cache_.reset();
    XLOG(INFO) << "CacheManager shutdown complete";
  }
}

GetResult CacheManager::get(std::string_view key) {
  GetResult result;
  ++getCount_;

  if (!cache_) {
    XLOG(WARN) << "Cache not initialized";
    return result;
  }

  try {
    auto handle = cache_->find(folly::StringPiece(key.data(), key.size()));

    if (handle) {
      ++hitCount_;
      result.found = true;

      // Copy the value
      const char* data = reinterpret_cast<const char*>(handle->getMemory());
      size_t size = handle->getSize();
      result.value.assign(data, size);

      // Calculate remaining TTL
      uint32_t expiryTime = handle->getExpiryTime();
      if (expiryTime > 0) {
        uint32_t now = static_cast<uint32_t>(
            std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::system_clock::now().time_since_epoch())
                .count());
        if (expiryTime > now) {
          result.ttlRemaining = static_cast<int64_t>(expiryTime - now);
        }
      }
    } else {
      ++missCount_;
    }
  } catch (const std::exception& ex) {
    XLOG(ERR) << "Error during get for key=" << key << ": " << ex.what();
    ++missCount_;
  }

  return result;
}

bool CacheManager::set(std::string_view key,
                       std::string_view value,
                       uint32_t ttlSeconds) {
  ++setCount_;

  if (!cache_) {
    XLOG(WARN) << "Cache not initialized";
    return false;
  }

  if (value.size() > config_.maxItemSize) {
    XLOG(WARN) << "Value too large: " << value.size()
               << " > " << config_.maxItemSize;
    return false;
  }

  try {
    // Allocate space for the item
    auto handle = cache_->allocate(
        defaultPoolId_,
        folly::StringPiece(key.data(), key.size()),
        static_cast<uint32_t>(value.size()),
        ttlSeconds);

    if (!handle) {
      XLOG(WARN) << "Failed to allocate cache item for key=" << key
                 << " size=" << value.size();
      return false;
    }

    // Copy the value into the allocated space
    std::memcpy(handle->getMemory(), value.data(), value.size());

    // Insert into cache (replaces existing item if present)
    cache_->insertOrReplace(handle);

    return true;
  } catch (const std::exception& ex) {
    XLOG(ERR) << "Error during set for key=" << key << ": " << ex.what();
    return false;
  }
}

bool CacheManager::remove(std::string_view key) {
  if (!cache_) {
    XLOG(WARN) << "Cache not initialized";
    return false;
  }

  try {
    auto result = cache_->remove(folly::StringPiece(key.data(), key.size()));
    return result == Cache::RemoveRes::kSuccess;
  } catch (const std::exception& ex) {
    XLOG(ERR) << "Error during remove for key=" << key << ": " << ex.what();
    return false;
  }
}

bool CacheManager::exists(std::string_view key) {
  if (!cache_) {
    return false;
  }

  try {
    auto handle = cache_->find(folly::StringPiece(key.data(), key.size()));
    return handle != nullptr;
  } catch (const std::exception& ex) {
    XLOG(ERR) << "Error during exists check for key=" << key << ": "
              << ex.what();
    return false;
  }
}

std::vector<GetResult> CacheManager::multiGet(
    const std::vector<std::string>& keys) {
  std::vector<GetResult> results;
  results.reserve(keys.size());

  for (const auto& key : keys) {
    results.push_back(get(key));
  }

  return results;
}

CacheStats CacheManager::getStats() const {
  CacheStats stats;

  if (!cache_) {
    return stats;
  }

  try {
    auto memStats = cache_->getCacheMemoryStats();
    auto globalStats = cache_->getGlobalCacheStats();

    stats.totalSize = static_cast<int64_t>(memStats.ramCacheSize);
    stats.usedSize = static_cast<int64_t>(
        memStats.ramCacheSize - memStats.unReservedSize);
    stats.itemCount = static_cast<int64_t>(globalStats.numItems);
    stats.evictionCount = static_cast<int64_t>(globalStats.numEvictions);

    stats.getCount = getCount_.load();
    stats.hitCount = hitCount_.load();
    stats.missCount = missCount_.load();
    stats.setCount = setCount_.load();

    if (stats.getCount > 0) {
      stats.hitRate =
          static_cast<double>(stats.hitCount) / static_cast<double>(stats.getCount);
    }

    // NVM stats if enabled
    stats.nvmEnabled = config_.enableNvm;
    if (config_.enableNvm) {
      auto nvmStatsMap = cache_->getNvmCacheStatsMap();
      auto nvmStats = nvmStatsMap.toMap();
      stats.nvmSize = static_cast<int64_t>(config_.nvmCacheSize);
      // NVM used bytes would come from Navy stats
      auto it = nvmStats.find("navy_device_bytes_written");
      if (it != nvmStats.end()) {
        stats.nvmUsed = static_cast<int64_t>(it->second);
      }
    }

    // Uptime
    auto now = std::chrono::steady_clock::now();
    stats.uptimeSeconds = static_cast<int64_t>(
        std::chrono::duration_cast<std::chrono::seconds>(now - startTime_)
            .count());

  } catch (const std::exception& ex) {
    XLOG(ERR) << "Error getting stats: " << ex.what();
  }

  return stats;
}

}  // namespace grpc_server
}  // namespace cachelib
