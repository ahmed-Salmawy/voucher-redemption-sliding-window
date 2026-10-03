package com.example.voucher.concurrency;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * JVM-level mutual exclusion per user: two concurrent redeems by the same user in THIS JVM
 * run one after the other; different users mostly don't contend.
 *
 * Approach: striped locks - a fixed array of ReentrantLock, picked by hash(userId) % N.
 * (A ConcurrentHashMap<userId, lock> grows forever unless you evict; stripes are bounded.)
 *
 * ponytail ceiling: only protects ONE JVM. With N instances behind a load balancer this is
 * not enough - that is why the Redis script is atomic as well. Document that in the README.
 */
@Component
public class UserLockRegistry {

    private static final int STRIPES = 256;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public UserLockRegistry() {
        for (int i = 0; i < STRIPES; i++) locks[i] = new ReentrantLock();
    }

    /**
     * TODO: pick the stripe (watch out: Math.abs(Integer.MIN_VALUE) is still negative!),
     *       lock, run the action, unlock in finally, return the result.
     * TODO: tryLock with timeout vs lock()? What should the caller see on timeout?
     */
    public <T> T withLock(String userId, java.util.function.Supplier<T> action) {
        throw new UnsupportedOperationException("TODO");
    }
}
