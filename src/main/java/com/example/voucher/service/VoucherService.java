package com.example.voucher.service;

import com.example.voucher.concurrency.UserLockRegistry;
import com.example.voucher.domain.Redemption;
import com.example.voucher.domain.Voucher;
import com.example.voucher.exception.RateLimitExceededException;
import com.example.voucher.exception.VoucherSoldOutException;
import com.example.voucher.ratelimit.SlidingWindowRateLimiter;
import com.example.voucher.repo.RedemptionRepository;
import com.example.voucher.repo.VoucherRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class VoucherService {

    private final VoucherRepository vouchers;
    private final RedemptionRepository redemptions;
    private final SlidingWindowRateLimiter limiter;
    private final UserLockRegistry locks;
    private final TransactionTemplate tx;
    private final TransactionTemplate transactionTemplate;

    public VoucherService(VoucherRepository vouchers, RedemptionRepository redemptions,
                          SlidingWindowRateLimiter limiter, UserLockRegistry locks,
                          TransactionTemplate tx, TransactionTemplate transactionTemplate) {
        this.vouchers = vouchers;
        this.redemptions = redemptions;
        this.limiter = limiter;
        this.locks = locks;
        this.tx = tx;
        this.transactionTemplate = transactionTemplate;
    }

    public List<Voucher> list() {
        // TODO: return vouchers with remaining > 0 (add a derived query to the repo). Pagination? Out of scope - say so in README.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Intended flow (ORDER MATTERS - reason about each step in the README):
     * <p>
     * locks.withLock(userId, () -> {
     * 1. token = limiter.tryAcquire(userId)          -> empty => throw RateLimitExceededException
     * 2. try {
     * tx.execute(...) {                        -> programmatic tx, NOT @Transactional on this method
     * rows = vouchers.decrementStock(id)   -> 0 => throw VoucherSoldOutException
     * redemptions.save(new Redemption(userId, id))
     * }
     * } catch (RuntimeException e) {
     * limiter.release(userId, token);          -> failed redeem must not burn quota
     * throw e;
     * }
     * });
     * <p>
     * Why programmatic tx: the lock must be released AFTER commit. With @Transactional on the
     * method the commit happens after the method returns, i.e. after the lock is released, and
     * a second thread can enter before the first one's row is visible.
     * <p>
     * TODO: implement. Then answer in README: is the JVM lock even needed given the Lua script is atomic?
     */
    public void redeem(String userId, Long voucherId) {

        if (limiter.tryAcquire(userId).isEmpty()) {
            throw new RateLimitExceededException("Redemption limit reached, try again later");
        }

        transactionTemplate.executeWithoutResult(transactionStatus -> {
            int count = vouchers.decrementStock(voucherId);
            if(count == 0){
                throw new VoucherSoldOutException();
            }
            redemptions.save(new Redemption(userId, voucherId));
        });
    }
}
