package com.example.voucher.exception;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String s) {
        super(s);
    }
}
