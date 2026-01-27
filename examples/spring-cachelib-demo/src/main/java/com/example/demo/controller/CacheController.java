package com.example.demo.controller;

import com.example.demo.client.CacheLibClient;
import com.facebook.cachelib.grpc.StatsResponse;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/cache")
public class CacheController {

    private final CacheLibClient cache;

    public CacheController(CacheLibClient cache) {
        this.cache = cache;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", cache.ping() ? "UP" : "DOWN");
        return response;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        StatsResponse stats = cache.getStats();
        Map<String, Object> response = new HashMap<>();
        response.put("totalSizeBytes", stats.getTotalSize());
        response.put("usedSizeBytes", stats.getUsedSize());
        response.put("itemCount", stats.getItemCount());
        response.put("hitRate", String.format("%.2f%%", stats.getHitRate() * 100));
        response.put("getCount", stats.getGetCount());
        response.put("hitCount", stats.getHitCount());
        response.put("missCount", stats.getMissCount());
        response.put("setCount", stats.getSetCount());
        response.put("evictionCount", stats.getEvictionCount());
        response.put("uptimeSeconds", stats.getUptimeSeconds());
        return response;
    }

    @PostMapping("/set")
    public Map<String, Object> set(@RequestParam String key,
                                    @RequestParam String value,
                                    @RequestParam(defaultValue = "300") int ttl) {
        boolean success = cache.set(key, value, ttl);
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("key", key);
        return response;
    }

    @GetMapping("/get")
    public Map<String, Object> get(@RequestParam String key) {
        Map<String, Object> response = new HashMap<>();
        response.put("key", key);
        cache.get(key).ifPresentOrElse(
                value -> {
                    response.put("found", true);
                    response.put("value", value);
                },
                () -> response.put("found", false)
        );
        return response;
    }

    @DeleteMapping("/delete")
    public Map<String, Object> delete(@RequestParam String key) {
        boolean success = cache.delete(key);
        Map<String, Object> response = new HashMap<>();
        response.put("success", success);
        response.put("key", key);
        return response;
    }
}
