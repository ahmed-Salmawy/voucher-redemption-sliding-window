package com.example.voucher.repo;

import com.example.voucher.domain.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {


    // Think: why is this safe across JVMs while a JVM lock is not?

    @Modifying
    @Query("update Voucher v set v.remaining = v.remaining - 1 where v.id = :id and v.remaining > 0 ")
    int decrementStock(@Param("id") Long id);
}
