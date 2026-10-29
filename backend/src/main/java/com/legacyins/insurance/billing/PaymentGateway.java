package com.legacyins.insurance.billing;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Deterministic payment gateway simulator. Card refs ending 0002/0003/0005 decline, 0004 times out;
 * amounts with 13 cents or above 50,000 decline; bank accounts starting 000 bounce. Cash/cheque always settle.
 */
@Component
public class PaymentGateway {
    public record Result(boolean approved, String code, String txnRef, String reason) {
    }

    public Result charge(String method, String instrumentRef, BigDecimal amount) {
        String txn = "GW-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        if ("CASH".equals(method) || "CHEQUE".equals(method)) {
            return new Result(true, "00", txn, null);
        }
        String ref = instrumentRef == null ? "" : instrumentRef.replaceAll("\\s", "");
        if (ref.isEmpty()) {
            return new Result(false, "14", null, "Missing instrument reference");
        }
        if ("CARD".equals(method)) {
            if (ref.endsWith("0004")) {
                return new Result(false, "TIMEOUT", null, "Gateway timeout");
            }
            if (ref.endsWith("0002")) {
                return new Result(false, "05", null, "Do not honour");
            }
            if (ref.endsWith("0003")) {
                return new Result(false, "51", null, "Insufficient funds");
            }
            if (ref.endsWith("0005")) {
                return new Result(false, "54", null, "Expired card");
            }
        }
        if ("BANK".equals(method) && ref.startsWith("000")) {
            return new Result(false, "R01", null, "Account closed / bounced");
        }
        if (amount.compareTo(new BigDecimal("50000")) > 0) {
            return new Result(false, "61", null, "Exceeds withdrawal limit");
        }
        if (amount.remainder(BigDecimal.ONE).compareTo(new BigDecimal("0.13")) == 0) {
            return new Result(false, "05", null, "Do not honour (risk rule)");
        }
        return new Result(true, "00", txn, null);
    }
}
