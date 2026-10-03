package com.example.voucher;

import com.example.voucher.exception.AlreadyRedeemedException;
import com.example.voucher.exception.RateLimitExceededException;
import com.example.voucher.exception.VoucherSoldOutException;
import com.example.voucher.repo.RedemptionRepository;
import com.example.voucher.repo.VoucherRepository;
import com.example.voucher.service.VoucherService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired JdbcTemplate jdbc;

    @Test
    void sameUserFiringManyParallelRequests_onlyFiveSucceed() throws Exception {
        // Fresh user per run: no cleanup of Redis/Postgres needed, old runs can't leak into the counts.
        String userId = "race-" + UUID.randomUUID();
        // One voucher per request: (user, voucher) is unique now, so reusing a voucher would throw
        // AlreadyRedeemedException instead of testing the rate limit. Fresh ones: seeded stock may be gone.
        List<Long> voucherIds = java.util.stream.IntStream.range(0, THREADS).mapToObj(i -> newVoucher(100)).toList();

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        CountDownLatch ready = new CountDownLatch(THREADS);   // all threads are parked at the gate
        CountDownLatch go = new CountDownLatch(1);            // opened once, releases everyone together

        ExecutorService pool = Executors.newFixedThreadPool(THREADS); // one thread per task, or they won't collide
        try {
            List<Callable<Void>> tasks = java.util.stream.IntStream.range(0, THREADS)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        Long voucherId = voucherIds.get(i);
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

    // ---------- Stage 3: stock integrity ----------

    @Test
    void lastVoucherWithManyUsers_exactlyOneWins() throws Exception {
        long voucherId = newVoucher(1);

        List<Throwable> results = fire(20, i -> service.redeem("last-" + UUID.randomUUID(), voucherId));

        assertThat(results.stream().filter(t -> t == null)).as("successes").hasSize(1);
        assertThat(results.stream().filter(t -> t instanceof VoucherSoldOutException)).as("sold out").hasSize(19);
        assertThat(remaining(voucherId)).as("remaining").isZero();      // never negative
        assertThat(redemptionRows(voucherId)).as("redemption rows").isEqualTo(1);
    }

    @Test
    void stockOfFive_thirtyUsers_exactlyFiveWin() throws Exception {
        long voucherId = newVoucher(5);

        List<Throwable> results = fire(30, i -> service.redeem("stock-" + UUID.randomUUID(), voucherId));

        assertThat(results.stream().filter(t -> t == null)).hasSize(5);
        assertThat(results.stream().filter(t -> t instanceof VoucherSoldOutException)).hasSize(25);
        assertThat(remaining(voucherId)).isZero();
        assertThat(redemptionRows(voucherId)).isEqualTo(5);
    }

    @Test
    void soldOutRedeem_leavesNoRedemptionRow() {
        long voucherId = newVoucher(0);

        assertThatThrownBy(() -> service.redeem("so-" + UUID.randomUUID(), voucherId))
                .isInstanceOf(VoucherSoldOutException.class);

        assertThat(redemptionRows(voucherId)).isZero();
        assertThat(remaining(voucherId)).isZero();
    }

    @Test
    @Transactional   // @Modifying needs a tx; rolled back after the test
    void decrementStock_returnsOneThenZero() {
        long voucherId = newVoucher(1);

        assertThat(vouchers.decrementStock(voucherId)).isEqualTo(1);
        assertThat(vouchers.decrementStock(voucherId)).isZero();         // 0 => sold out
        assertThat(remaining(voucherId)).isZero();
    }

    @Test
    void dbCheckConstraint_rejectsNegativeStock() {
        long voucherId = newVoucher(0);

        // Backstop if app code is ever buggy. If this fails, your volume predates the CHECK:
        // CREATE TABLE IF NOT EXISTS won't alter it. `docker compose down -v` and start again.
        assertThatThrownBy(() -> jdbc.update("UPDATE voucher SET remaining = -1 WHERE id = ?", voucherId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Disabled("TODO: implement and enable")
    @Test
    void windowSlides_sixthRequestAllowedAfterOldestExpires() {
        // TODO: use a short window (e.g. 2s via test property), do 5, expect 6th rejected, sleep, expect 6th ok.
    }

    @Test
    void failedDbWrite_releasesTheSlot() {
        String userId = "rel-" + UUID.randomUUID();
        long soldOut = newVoucher(0);

        // 5 failed redemptions: if each leaked its slot, the user would now be rate limited.
        for (int i = 0; i < LIMIT; i++) {
            assertThatThrownBy(() -> service.redeem(userId, soldOut)).isInstanceOf(VoucherSoldOutException.class);
        }

        // Full quota is still available...
        for (int i = 0; i < LIMIT; i++) service.redeem(userId, newVoucher(10));
        // ...and the cap still holds.
        assertThatThrownBy(() -> service.redeem(userId, newVoucher(10))).isInstanceOf(RateLimitExceededException.class);
    }

    // ---------- one redemption per (user, voucher) ----------

    @Test
    void sameUserSameVoucherTwice_secondRejected() {
        String userId = "dup-" + UUID.randomUUID();
        long voucherId = newVoucher(10);

        service.redeem(userId, voucherId);

        assertThatThrownBy(() -> service.redeem(userId, voucherId)).isInstanceOf(AlreadyRedeemedException.class);
        assertThat(redemptionRows(voucherId)).isEqualTo(1);
        assertThat(remaining(voucherId)).isEqualTo(9);                  // the rejected attempt took no stock
    }

    @Test
    void alreadyRedeemed_doesNotBurnRateLimitSlot() {
        String userId = "dupslot-" + UUID.randomUUID();
        long first = newVoucher(10);
        service.redeem(userId, first);                                  // slot 1 of 5

        for (int i = 0; i < 10; i++) {                                  // would exhaust the limit if they counted
            assertThatThrownBy(() -> service.redeem(userId, first)).isInstanceOf(AlreadyRedeemedException.class);
        }

        for (int i = 0; i < LIMIT - 1; i++) service.redeem(userId, newVoucher(10));   // slots 2..5 still free
        assertThatThrownBy(() -> service.redeem(userId, newVoucher(10))).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void sameUserSameVoucherInParallel_exactlyOneWins() throws Exception {
        String userId = "dupraw-" + UUID.randomUUID();
        long voucherId = newVoucher(10);
        int n = LIMIT;                                                  // <= limit, so only the duplicate rule can reject

        List<Throwable> results = fire(n, i -> service.redeem(userId, voucherId));

        assertThat(results.stream().filter(t -> t == null)).as("successes").hasSize(1);
        assertThat(results.stream().filter(t -> t instanceof AlreadyRedeemedException)).as("duplicates").hasSize(n - 1);
        assertThat(redemptionRows(voucherId)).isEqualTo(1);
        assertThat(remaining(voucherId)).as("losers' stock decrement rolled back").isEqualTo(9);

        // Losers that got past the pre-check and failed on the unique constraint must have released their slot:
        // 1 used by the winner, so 4 more redemptions must still be possible.
        for (int i = 0; i < LIMIT - 1; i++) service.redeem(userId, newVoucher(10));
    }

    @Test
    void dbUniqueConstraint_rejectsDuplicateUserVoucher() {
        long voucherId = newVoucher(10);
        jdbc.update("INSERT INTO redemption (user_id, voucher_id) VALUES ('u', ?)", voucherId);

        // Backstop under the pre-check (which is racy by itself). If this fails with no exception, your volume
        // predates the constraint: CREATE TABLE IF NOT EXISTS won't alter it, run `docker compose down -v`.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO redemption (user_id, voucher_id) VALUES ('u', ?)", voucherId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- helpers ----------

    /** Fresh voucher per test so runs don't depend on (or eat into) the seeded stock. */
    private long newVoucher(int remaining) {
        return jdbc.queryForObject(
                "INSERT INTO voucher (code, title, remaining) VALUES (?, 'test', ?) RETURNING id",
                Long.class, "T-" + UUID.randomUUID(), remaining);
    }

    private int remaining(long voucherId) {
        return jdbc.queryForObject("SELECT remaining FROM voucher WHERE id = ?", Integer.class, voucherId);
    }

    private int redemptionRows(long voucherId) {
        return jdbc.queryForObject("SELECT count(*) FROM redemption WHERE voucher_id = ?", Integer.class, voucherId);
    }

    /** Runs call(i) on n threads released together; returns null per success, the exception per failure. */
    private List<Throwable> fire(int n, IntConsumer call) throws Exception {
        List<Throwable> results = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        call.accept(idx);
                        results.add(null);
                    } catch (Throwable t) {
                        results.add(t);
                    }
                }));
            }
            ready.await();
            go.countDown();
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        return results;
    }
}
