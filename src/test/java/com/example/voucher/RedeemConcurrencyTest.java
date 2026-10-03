package com.example.voucher;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * The proof of the case study. Needs Postgres + Redis (docker compose up -d) or Testcontainers.
 * Enable each test as you implement the matching piece.
 */
@Disabled("TODO: implement and enable")
class RedeemConcurrencyTest {

    @Test
    void sameUserFiringManyParallelRequests_onlyFiveSucceed() {
        // TODO: 50 threads, a CountDownLatch start gate so they really collide, one userId, different vouchers.
        //       Assert: exactly 5 successes, 45 RateLimitExceeded, 5 rows in redemption.
    }

    @Test
    void lastVoucherWithManyUsers_exactlyOneWins() {
        // TODO: voucher LAST1 (remaining=1), 20 different users at once. Assert: 1 success, remaining=0, never negative.
    }

    @Test
    void windowSlides_sixthRequestAllowedAfterOldestExpires() {
        // TODO: use a short window (e.g. 2s via test property), do 5, expect 6th rejected, sleep, expect 6th ok.
    }

    @Test
    void failedDbWrite_releasesTheSlot() {
        // TODO: force the tx to fail (sold-out voucher), assert the user's window count is unchanged.
    }
}
