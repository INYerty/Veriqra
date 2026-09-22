package io.github.lz007001cn.veriqra.web.security;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {
    final AtomicLong now = new AtomicLong();
    LoginRateLimiter limiter(int pair, int ip, int cap) {
        return new LoginRateLimiter(pair, ip, cap, Duration.ofSeconds(60), now::get);
    }
    @Test void failuresLockOnlyForWindowAndCaseVariantsShareBudget() {
        var l = limiter(2, 5, 10);
        l.acquire("ip", "Admin").failure(); l.acquire("ip", "ADMIN ").failure();
        assertEquals(60, assertThrows(LoginRateLimiter.Limited.class, () -> l.acquire("ip", "admin")).retryAfter());
        now.set(Duration.ofSeconds(61).toNanos());
        l.acquire("ip", "admin").success();
        assertEquals(0, l.trackedBuckets());
    }
    @Test void successClearsPairButDoesNotEraseIpFailures() {
        var l = limiter(3, 4, 20);
        l.acquire("ip", "a").failure(); l.acquire("ip", "a").success();
        l.acquire("ip", "a").failure(); l.acquire("ip", "a").failure();
        l.acquire("ip", "b").failure();
        assertThrows(LoginRateLimiter.Limited.class, () -> l.acquire("ip", "c"));
    }
    @Test void otherIpCanUseLockedUsernameAndOtherUsernameCanUsePairLimitedIp() {
        var l = limiter(1, 4, 20);
        l.acquire("ip1", "a").failure();
        l.acquire("ip2", "a").success(); l.acquire("ip1", "b").success();
        assertThrows(LoginRateLimiter.Limited.class, () -> l.acquire("ip1", "a"));
    }
    @Test void mapIsBoundedFailsClosedAndReclaimsExpiredEntries() {
        var l = limiter(2, 5, 4);
        l.acquire("ip1", "a").failure(); l.acquire("ip2", "a").failure();
        for (int i=0; i<100; i++) {
            String ip="new"+i;
            assertThrows(LoginRateLimiter.Limited.class, () -> l.acquire(ip, "a"));
            assertEquals(4, l.trackedBuckets());
        }
        now.set(Duration.ofSeconds(61).toNanos());
        l.acquire("ip3", "b").success(); assertEquals(0, l.trackedBuckets());
    }
    @Test void abortedVerificationReleasesReservationAndDoubleCloseIsSafe() {
        var l = limiter(1, 2, 4);
        var p=l.acquire("ip", "a"); p.close(); p.close(); p.failure();
        assertEquals(0, l.trackedBuckets());
        l.acquire("ip", "a").success();
    }
    @Test void concurrentAcquisitionCannotExceedPairBudget() throws Exception {
        var l=limiter(2, 10, 30);
        var start=new CountDownLatch(1); var release=new CountDownLatch(1);
        var admitted=new CountDownLatch(2);
        try (var executor=Executors.newFixedThreadPool(8)) {
            var futures=new java.util.ArrayList<Future<Boolean>>();
            for(int i=0;i<8;i++) futures.add(executor.submit(() -> {
                start.await();
                try(var p=l.acquire("ip", "a")) {
                    admitted.countDown(); release.await(); p.failure(); return true;
                } catch(LoginRateLimiter.Limited e) { return false; }
            }));
            start.countDown();
            try { assertTrue(admitted.await(5, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            int count=0; for(var f:futures) if(f.get(5, TimeUnit.SECONDS)) count++;
            assertEquals(2,count);
        }
    }
}
