package com.example.voucher.repo;

import com.example.voucher.domain.Redemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public interface RedemptionRepository extends JpaRepository<Redemption, Long> {
    // TODO (optional): count by user within last hour - used ONLY to cross-check Redis in tests,
    //                  Postgres is the durable record, Redis is the fast gate.


    @Query("""
            SELECT COUNT(r)
            FROM Redemption r
            WHERE r.userId = :userId
              AND r.redeemedAt >= :cutoff
            """)
    long countByUserIdSince(
            @Param("userId") String userId,
            @Param("cutoff") Instant cutoff
    );



}
