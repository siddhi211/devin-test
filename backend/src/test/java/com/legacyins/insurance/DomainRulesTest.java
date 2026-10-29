package com.legacyins.insurance;

import com.legacyins.insurance.claims.ClaimService;
import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.Money;
import com.legacyins.insurance.product.PremiumService;
import com.legacyins.insurance.reporting.ReportService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainRulesTest {

    @Test
    void moneyRoundsHalfUpToCents() {
        assertThat(Money.of(new BigDecimal("10.005"))).isEqualByComparingTo("10.01");
        assertThat(Money.of(null)).isEqualByComparingTo("0.00");
        assertThat(Money.pct(new BigDecimal("1000"), new BigDecimal("12.5"))).isEqualByComparingTo("125.00");
    }

    @Test
    void installmentsPerYearByFrequency() {
        assertThat(PremiumService.installmentsPerYear("ANNUAL")).isEqualTo(1);
        assertThat(PremiumService.installmentsPerYear("SEMI")).isEqualTo(2);
        assertThat(PremiumService.installmentsPerYear("QUARTERLY")).isEqualTo(4);
        assertThat(PremiumService.installmentsPerYear("MONTHLY")).isEqualTo(12);
        assertThatThrownBy(() -> PremiumService.installmentsPerYear("WEEKLY")).isInstanceOf(ApiException.class);
    }

    @Test
    void ratingRuleOperators() {
        assertThat(PremiumService.matches(45, "GTE", "45")).isTrue();
        assertThat(PremiumService.matches(new BigDecimal("999999"), "GT", "1000000")).isFalse();
        assertThat(PremiumService.matches("Y", "EQ", "Y")).isTrue();
        assertThat(PremiumService.matches("SUB", "IN", "SUB,HIGH")).isTrue();
        assertThat(PremiumService.matches(null, "EQ", "Y")).isFalse();
        assertThat(PremiumService.matches(null, "ANY", null)).isTrue();
    }

    @Test
    void partialClaimDisplaysLegacyStatus() {
        assertThat(ClaimService.displayStatus("PARTIAL")).isEqualTo("PARTIALLY_APPROVED");
        assertThat(ClaimService.displayStatus("APPROVED")).isEqualTo("APPROVED");
    }

    @Test
    void csvEscapesQuotesAndBlocksFormulaInjection() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "Smith, \"Jr\"");
        row.put("note", "=HYPERLINK(\"x\")");
        row.put("amount", -12.5);
        String csv = ReportService.toCsv(Map.of("columns", List.of("name", "note", "amount"), "rows", List.of(row)));
        String[] lines = csv.split("\n");
        assertThat(lines[0]).isEqualTo("name,note,amount");
        assertThat(lines[1]).isEqualTo("\"Smith, \"\"Jr\"\"\",\"'=HYPERLINK(\"\"x\"\")\",-12.5");
    }
}
