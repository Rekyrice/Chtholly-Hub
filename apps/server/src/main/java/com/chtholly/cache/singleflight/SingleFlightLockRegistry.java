package com.chtholly.cache.singleflight;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * SingleFlight 锁注册表：对同一 key 串行执行，防止缓存击穿。
 * <p>
 * 使用 {@code computeIfAbsent + synchronized}，在 {@code finally} 中 {@code remove(key, lock)}
 * 确保异常路径也会清理；Caffeine {@code expireAfterAccess} 作为兜底，避免极端泄漏。
 */
public final class SingleFlightLockRegistry {

    private final ConcurrentMap<String, Object> locks;

    public SingleFlightLockRegistry() {
        this(5);
    }

    public SingleFlightLockRegistry(long expireAfterAccessMinutes) {
        Cache<String, Object> cache = Caffeine.newBuilder()
                .expireAfterAccess(expireAfterAccessMinutes, TimeUnit.MINUTES)
                .build();
        this.locks = cache.asMap();
    }

    /**
     * 对 key 加互斥锁执行 action；相同 key 的并发调用在锁上排队。
     */
    public <T> T runExclusive(String key, Supplier<T> action) {
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                return action.get();
            }
        } finally {
            locks.remove(key, lock);
        }
    }

    /** 当前仍注册的锁 key 数量（主要用于测试）。 */
    int registeredCount() {
        return locks.size();
    }
}
