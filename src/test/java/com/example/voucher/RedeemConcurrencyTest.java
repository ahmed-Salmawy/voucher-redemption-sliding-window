package com.example.voucher;

import com.example.voucher.exception.RateLimitExceededException;
import com.example.voucher.repo.RedemptionRepository;
import com.example.voucher.repo.VoucherRepository;
import com.example.voucher.service.VoucherService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proof of the case study. Needs Postgres + Redis (docker compose up -d) or Testcontainers.
 * Enable each test as you implement the matching piece.
 */
@SpringBootTest
class RedeemConcurrencyTest {

    private static final int THREADS = 50;
    private static final int LIMIT = 5;

    @Autowired VoucherService service;
    @Autowired VoucherRepository vouchers;
    @Autowired RedemptionRepository redemptions;

    @Test
    void sameUserFiringManyParallelRequests_onlyFiveSucceed() throws Exception {
        // Fresh user per run: no cleanup of Redis/Postgres needed, old runs can't leak into the counts.
        String userId = "race-" + UUID.randomUUID();
        // Different vouchers on purpose: proves the limit is per user, not per (user, voucher).
        List<Long> voucherIds = vouchers.findAll().stream().map(v -> v.getId()).toList();

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        CountDownLatch ready = new CountDownLatch(THREADS);   // all threads are parked at the gate
        CountDownLatch go = new CountDownLatch(1);            // opened once, releases everyone together

        ExecutorService pool = Executors.newFixedThreadPool(THREADS); // one thread per task, or they won't collide
        try {
            List<Callable<Void>> tasks = java.util.stream.IntStream.range(0, THREADS)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        Long voucherId = voucherIds.get(i % voucherIds.size());
                        ready.countDown();
                        go.await();
                        try {
                            service.redeem(userId, voucherId);
                            ok.incrementAndGet();
                        } catch (RateLimitExceededException e) {
                            rateLimited.incrementAndGet();
                        } catch (Throwable t) {
                            unexpected.incrementAndGet();   // surface anything else instead of hiding it
                            t.printStackTrace();
                        }
                        return null;
                    })
                    .toList();

            List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
            ready.await();
            go.countDown();
            for (Future<Void> f : futures) f.get();         // rethrows if a task itself blew up
        } finally {
            pool.shutdownNow();
        }

        assertThat(unexpected.get()).as("unexpected exceptions").isZero();
        assertThat(ok.get()).as("successes").isEqualTo(LIMIT);
        assertThat(rateLimited.get()).as("rate limited").isEqualTo(THREADS - LIMIT);

        // Cross-check against Postgres, the durable record.
        long rows = redemptions.countByUserIdSince(userId, Instant.now().minus(1, ChronoUnit.HOURS));
        assertThat(rows).as("rows in redemption").isEqualTo(LIMIT);
    }

    @Disabled("TODO: implement and enable")
    @Test
    void lastVoucherWithManyUsers_exactlyOneWins() {
        // TODO: voucher LAST1 (remaining=1), 20 different users at once. Assert: 1 success, remaining=0, never negative.
    }

    @Disabled("TODO: implement and enable")
    @Test
    void windowSlides_sixthRequestAllowedAfterOldestExpires() {
        // TODO: use a short window (e.g. 2s via test property), do 5, expect 6th rejected, sleep, expect 6th ok.
    }

    @Disabled("TODO: implement and enable")
    @Test
    void failedDbWrite_releasesTheSlot() {
        // TODO: force the tx to fail (sold-out voucher), assert the user's window count is unchanged.
    }
}
