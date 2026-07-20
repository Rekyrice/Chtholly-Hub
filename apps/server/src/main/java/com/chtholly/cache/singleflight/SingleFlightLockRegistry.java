package com.chtholly.cache.singleflight;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 进程内 SingleFlight 锁注册表：同一个 key 串行执行，避免缓存击穿。
 *
 * <p>锁条目记录已经进入注册表的调用数。只有最后一个调用离开时才移除条目，
 * 避免旧等待者仍在执行期间，新调用创建第二把锁。</p>
 */
public final class SingleFlightLockRegistry {

    private final ConcurrentMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    /**
     * 对 key 加互斥锁执行 action；相同 key 的并发调用在同一锁条目上排队。
     */
    public <T> T runExclusive(String key, Supplier<T> action) {
        LockEntry entry = locks.compute(key, (ignored, current) -> {
            LockEntry registered = current == null ? new LockEntry() : current;
            registered.entrants++;
            return registered;
        });
        try {
            synchronized (entry.monitor) {
                return action.get();
            }
        } finally {
            locks.computeIfPresent(key, (ignored, current) -> {
                if (current != entry) {
                    return current;
                }
                entry.entrants--;
                return entry.entrants == 0 ? null : entry;
            });
        }
    }

    /** 当前仍注册的 key 数量，主要用于测试。 */
    int registeredCount() {
        return locks.size();
    }

    private static final class LockEntry {
        private final Object monitor = new Object();
        private int entrants;
    }
}
