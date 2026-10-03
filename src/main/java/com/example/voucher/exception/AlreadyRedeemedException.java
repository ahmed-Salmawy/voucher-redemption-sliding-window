package com.example.voucher.exception;

public class AlreadyRedeemedException extends RuntimeException {
    public AlreadyRedeemedException(String message) {
        super(message);
    }
}
