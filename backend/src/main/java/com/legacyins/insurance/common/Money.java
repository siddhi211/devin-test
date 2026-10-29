package com.legacyins.insurance.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    public static final BigDecimal HUNDRED = new BigDecimal("100");

    private Money() {
    }

    public static BigDecimal of(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    public static BigDecimal pct(BigDecimal base, BigDecimal pct) {
        return of(nz(base).multiply(nz(pct)).divide(HUNDRED, 6, RoundingMode.HALF_UP));
    }

    public static BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public static BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
