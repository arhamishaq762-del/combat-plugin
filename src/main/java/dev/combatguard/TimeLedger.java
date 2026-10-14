package dev.combatguard;

import java.util.*;
import java.util.function.LongSupplier;

/** Monotonic, main-thread deadline storage shared by tags and item cooldowns. */
final class TimeLedger<K> {
    private final Map<K, Long> deadlines = new HashMap<>();
    private final LongSupplier clock;
    TimeLedger(LongSupplier clock) { this.clock = clock; }
    void start(K key, double seconds) {
        if (seconds <= 0) { deadlines.remove(key); return; }
        deadlines.put(key, clock.getAsLong() + (long) Math.ceil(seconds * 1000));
    }
    long remainingMillis(K key) {
        long remaining = deadlines.getOrDefault(key, 0L) - clock.getAsLong();
        if (remaining <= 0) { deadlines.remove(key); return 0; }
        return remaining;
    }
    long remainingSeconds(K key) { return (remainingMillis(key) + 999) / 1000; }
    boolean active(K key) { return remainingMillis(key) > 0; }
    void remove(K key) { deadlines.remove(key); }
    void clear() { deadlines.clear(); }
    void prune() { long now = clock.getAsLong(); deadlines.values().removeIf(end -> end <= now); }
}
