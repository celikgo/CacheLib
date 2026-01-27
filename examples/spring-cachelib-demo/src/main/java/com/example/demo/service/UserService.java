package com.example.demo.service;

import com.example.demo.client.CacheLibClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * User service demonstrating CacheLib usage as a cache layer.
 * Simulates a database with in-memory storage and uses CacheLib for caching.
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final int CACHE_TTL_SECONDS = 300; // 5 minutes
    private static final String CACHE_PREFIX = "user:";

    private final CacheLibClient cache;
    private final ObjectMapper objectMapper;

    // Simulated database
    private final ConcurrentHashMap<Long, User> database = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong(1);

    public UserService(CacheLibClient cache) {
        this.cache = cache;
        this.objectMapper = new ObjectMapper();

        // Add some sample users
        createUser(new User(null, "john.doe@example.com", "John Doe"));
        createUser(new User(null, "jane.smith@example.com", "Jane Smith"));
        createUser(new User(null, "bob.wilson@example.com", "Bob Wilson"));
    }

    /**
     * Get user by ID - checks cache first, then database
     */
    public Optional<User> getUserById(Long id) {
        String cacheKey = CACHE_PREFIX + id;

        // Try cache first
        Optional<String> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            log.info("Cache HIT for user {}", id);
            try {
                return Optional.of(objectMapper.readValue(cached.get(), User.class));
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize cached user: {}", e.getMessage());
            }
        }

        log.info("Cache MISS for user {}", id);

        // Get from database
        User user = database.get(id);
        if (user != null) {
            // Store in cache
            try {
                String json = objectMapper.writeValueAsString(user);
                cache.set(cacheKey, json, CACHE_TTL_SECONDS);
                log.info("Cached user {}", id);
            } catch (JsonProcessingException e) {
                log.warn("Failed to cache user: {}", e.getMessage());
            }
            return Optional.of(user);
        }

        return Optional.empty();
    }

    /**
     * Create a new user
     */
    public User createUser(User user) {
        Long id = idGenerator.getAndIncrement();
        User newUser = new User(id, user.email(), user.name());
        database.put(id, newUser);
        log.info("Created user {} in database", id);
        return newUser;
    }

    /**
     * Update an existing user
     */
    public Optional<User> updateUser(Long id, User user) {
        if (!database.containsKey(id)) {
            return Optional.empty();
        }

        User updatedUser = new User(id, user.email(), user.name());
        database.put(id, updatedUser);

        // Invalidate cache
        String cacheKey = CACHE_PREFIX + id;
        cache.delete(cacheKey);
        log.info("Updated user {} and invalidated cache", id);

        return Optional.of(updatedUser);
    }

    /**
     * Delete a user
     */
    public boolean deleteUser(Long id) {
        User removed = database.remove(id);
        if (removed != null) {
            // Invalidate cache
            String cacheKey = CACHE_PREFIX + id;
            cache.delete(cacheKey);
            log.info("Deleted user {} and invalidated cache", id);
            return true;
        }
        return false;
    }

    /**
     * Get all users from database (not cached)
     */
    public Iterable<User> getAllUsers() {
        return database.values();
    }

    /**
     * User record
     */
    public record User(Long id, String email, String name) {}
}
