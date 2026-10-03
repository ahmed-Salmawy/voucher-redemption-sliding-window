package com.example.voucher.service;

import com.example.voucher.domain.Redemption;
import com.example.voucher.domain.Voucher;
import com.example.voucher.exception.AlreadyRedeemedException;
import com.example.voucher.exception.RateLimitExceededException;
import com.example.voucher.exception.VoucherSoldOutException;
import com.example.voucher.ratelimit.SlidingWindowRateLimiter;
import com.example.voucher.repo.RedemptionRepository;
import com.example.voucher.repo.VoucherRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class VoucherService {

    private final VoucherRepository vouchers;
    private final RedemptionRepository redemptions;
    private final SlidingWindowRateLimiter limiter;
    private final TransactionTemplate tx;
    private final TransactionTemplate transactionTemplate;

    public VoucherService(VoucherRepository vouchers, RedemptionRepository redemptions,
                          SlidingWindowRateLimiter limiter,
                          TransactionTemplate tx, TransactionTemplate transactionTemplate) {
        this.vouchers = vouchers;
        this.redemptions = redemptions;
        this.limiter = limiter;
        this.tx = tx;
        this.transactionTemplate = transactionTemplate;
    }

    public List<Voucher> list() {
        // TODO: return vouchers with remaining > 0 (add a derived query to the repo). Pagination? Out of scope - say so in README.
        throw new UnsupportedOperationException("TODO");
    }

    /**
     * Flow (order matters, see README section 3):
     * 1. pre-check "already redeemed?" (fast path only, the unique constraint is the real guard)
     * 2. take a slot in Redis (outside the transaction, so no DB connection is held while waiting)
     * 3. decrement stock + insert redemption in one transaction
     * 4. on any failure give the slot back; a failing release is logged and ignored
     */
    public void redeem(String userId, Long voucherId) {
        if (redemptions.findByUserIdAndVoucherId(userId, voucherId).isPresent()) {
            throw new AlreadyRedeemedException("Voucher Already redeemed once.");
        }
        String token = limiter.tryAcquire(userId)
                .orElseThrow(() ->
                        new RateLimitExceededException("Redemption limit reached"));
        try {
            transactionTemplate.executeWithoutResult(transactionStatus -> {

                int updatedCount = vouchers.decrementStock(voucherId);
                if (updatedCount == 0) {
                    throw new VoucherSoldOutException();
                }
                redemptions.save(new Redemption(userId, voucherId));

            });

        } catch (DataIntegrityViolationException ex) {
            limiter.release(userId, token);
            if (isConstraint(ex, "uq_redemption_user_voucher"))
                throw new AlreadyRedeemedException("Voucher already redeemed");

            throw ex;
        } catch (RuntimeException runtimeException) {
            limiter.release(userId, token);
            throw runtimeException;
        }

    }


    private boolean isConstraint(Throwable error, String constraintName) {
        Throwable current = error;

        while (current != null) {
            if (current instanceof
                    ConstraintViolationException violation
                    && constraintName.equals(violation.getConstraintName())) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}
