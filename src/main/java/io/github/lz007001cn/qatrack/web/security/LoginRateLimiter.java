package io.github.lz007001cn.qatrack.web.security;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/** Per-webapp, fixed-window failure limits. Reservations also bound concurrent password checks. */
public final class LoginRateLimiter {
    private final Map<Key, Bucket> buckets = new HashMap<>();
    private final int pairLimit, ipLimit, capacity;
    private final long window;
    private final LongSupplier ticker;

    public LoginRateLimiter() { this(5, 30, 4096, Duration.ofMinutes(5), System::nanoTime); }

    public LoginRateLimiter(int pairLimit, int ipLimit, int capacity, Duration window, LongSupplier ticker) {
        if (pairLimit < 1 || ipLimit < pairLimit || capacity < 2 || window.isNegative() || window.isZero())
            throw new IllegalArgumentException("Invalid login limiter bounds");
        this.pairLimit = pairLimit; this.ipLimit = ipLimit; this.capacity = capacity;
        this.window = window.toNanos(); this.ticker = Objects.requireNonNull(ticker);
    }

    public synchronized Permit acquire(String ip, String username) {
        long now = ticker.getAsLong();
        buckets.values().removeIf(b -> b.pending == 0 && now - b.started >= window);
        // AuthService permits ASCII usernames <=64; normalize case/trailing spaces like MySQL collation.
        String name = username == null || username.length() > 64 ? "<invalid>"
                : username.stripTrailing().toLowerCase(Locale.ROOT);
        if (ip == null || ip.length() > 64) throw new IllegalArgumentException("Invalid remote address");
        Key pairKey = new Key(ip, name), ipKey = new Key(ip, null);
        Bucket pair = buckets.get(pairKey), address = buckets.get(ipKey);
        refresh(pair, now); refresh(address, now);
        if (full(pair, pairLimit) || full(address, ipLimit)) {
            throw new Limited(Math.max(retry(pair, pairLimit, now), retry(address, ipLimit, now)));
        }
        int needed = (pair == null ? 1 : 0) + (address == null ? 1 : 0);
        if (buckets.size() + needed > capacity) throw new Limited(1);
        if (pair == null) { pair = new Bucket(now); buckets.put(pairKey, pair); }
        if (address == null) { address = new Bucket(now); buckets.put(ipKey, address); }
        pair.pending++; address.pending++;
        return new Permit(pairKey, ipKey, pair, address);
    }

    private void refresh(Bucket b, long now) {
        if (b != null && now - b.started >= window) { b.failures = 0; b.started = now; }
    }
    private boolean full(Bucket b, int limit) { return b != null && b.failures + b.pending >= limit; }
    private long retry(Bucket b, int limit, long now) {
        return full(b, limit) ? Math.max(1, (window - (now - b.started) + 999_999_999L) / 1_000_000_000L) : 1;
    }
    public synchronized int trackedBuckets() { return buckets.size(); }

    public final class Permit implements AutoCloseable {
        private final Key pairKey, ipKey;
        private final Bucket pair, address;
        private boolean finished;
        private Permit(Key pairKey, Key ipKey, Bucket pair, Bucket address) {
            this.pairKey = pairKey; this.ipKey = ipKey; this.pair = pair; this.address = address;
        }
        public void success() { finish(true, false); }
        public void failure() { finish(false, true); }
        @Override public void close() { finish(false, false); }
        private void finish(boolean success, boolean failure) {
            synchronized (LoginRateLimiter.this) {
                if (finished) return;
                finished = true;
                long now = ticker.getAsLong();
                refresh(pair, now); refresh(address, now);
                pair.pending--; address.pending--;
                if (success) pair.failures = 0;
                if (failure) { pair.failures++; address.failures++; }
                if (pair.pending == 0 && pair.failures == 0) buckets.remove(pairKey);
                if (address.pending == 0 && address.failures == 0) buckets.remove(ipKey);
            }
        }
    }
    public static final class Limited extends RuntimeException {
        private final long retryAfter;
        private Limited(long retryAfter) { super("Login temporarily limited"); this.retryAfter = retryAfter; }
        public long retryAfter() { return retryAfter; }
    }
    private record Key(String ip, String username) { }
    private static final class Bucket {
        long started; int failures, pending;
        Bucket(long started) { this.started = started; }
    }
}
