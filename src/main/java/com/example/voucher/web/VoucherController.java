package com.example.voucher.web;

import com.example.voucher.domain.Voucher;
import com.example.voucher.service.VoucherService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Auth is out of scope: the caller identity is the X-User-Id header. */
@RestController
@RequestMapping("/vouchers")
public class VoucherController {

    private final VoucherService service;

    public VoucherController(VoucherService service) {
        this.service = service;
    }

    @GetMapping
    public List<Voucher> list() {
        return service.list();
    }

    @PostMapping("/{id}/redeem")
    public void redeem(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) {
        service.redeem(userId, id);
    }

    // TODO: define RateLimitExceededException / VoucherSoldOutException (runtime exceptions) and map them with
    //       @ExceptionHandler here (or a @RestControllerAdvice):
    //         rate limited -> 429 + Retry-After header (hint: oldest ZSET entry score + window - now)
    //         sold out     -> 409
}
