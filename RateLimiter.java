package medtour;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A small in-memory brute-force guard for POST /api/auth/login, keyed by the email being attempted.
 *
 * Deliberately simple: a single-process ConcurrentHashMap, not a distributed store — this project
 * has no cache/Redis dependency and doesn't need one for a hackathon-scale deployment. It resets on
 * restart and doesn't coordinate across multiple backend instances; treat it as a speed bump against
 * casual credential stuffing, not a complete defense.
 */
public class RateLimiter {

    private static final int MAX_ATTEMPTS = 8;
    private static final long WINDOW_MS = 15 * 60 * 1000L; // 15 minutes

    private static final class Attempts {
        final AtomicInteger count = new AtomicInteger(0);
        volatile long windowStart = System.currentTimeMillis();
    }

    private static final ConcurrentHashMap<String, Attempts> ATTEMPTS_BY_KEY = new ConcurrentHashMap<>();

    public static boolean isBlocked(String key) {
        Attempts a = ATTEMPTS_BY_KEY.get(key);
        if (a == null) return false;
        if (System.currentTimeMillis() - a.windowStart > WINDOW_MS) {
            ATTEMPTS_BY_KEY.remove(key);
            return false;
        }
        return a.count.get() >= MAX_ATTEMPTS;
    }

    public static void recordFailure(String key) {
        Attempts a = ATTEMPTS_BY_KEY.computeIfAbsent(key, k -> new Attempts());
        long now = System.currentTimeMillis();
        if (now - a.windowStart > WINDOW_MS) {
            a.windowStart = now;
            a.count.set(1);
        } else {
            a.count.incrementAndGet();
        }
    }

    public static void recordSuccess(String key) {
        ATTEMPTS_BY_KEY.remove(key);
    }
}
