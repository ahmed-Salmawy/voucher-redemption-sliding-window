package com.example.voucher.repo;

import com.example.voucher.domain.Redemption;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RedemptionRepository extends JpaRepository<Redemption, Long> {
    // TODO (optional): count by user within last hour - used ONLY to cross-check Redis in tests,
    //                  Postgres is the durable record, Redis is the fast gate.
}
