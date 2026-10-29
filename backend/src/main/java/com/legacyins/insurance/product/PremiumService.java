package com.legacyins.insurance.product;

import com.legacyins.insurance.common.ApiException;
import com.legacyins.insurance.common.ErrorCode;
import com.legacyins.insurance.common.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Table-driven premium rule engine: base rate by age band/risk class, ordered adjustments, modal factor, rounding. */
@Service
@RequiredArgsConstructor
public class PremiumService {
    private static final Map<String, BigDecimal> MODAL_FACTOR = Map.of(
            "ANNUAL", BigDecimal.ONE,
            "SEMI", new BigDecimal("1.02"),
            "QUARTERLY", new BigDecimal("1.03"),
            "MONTHLY", new BigDecimal("1.05"));
    private static final Map<String, Integer> INSTALLMENTS = Map.of("ANNUAL", 1, "SEMI", 2, "QUARTERLY", 4, "MONTHLY", 12);

    private final ProductRepository products;
    private final JdbcTemplate jdbc;

    public record QuoteRequest(Long productId, Long customerId, LocalDate dateOfBirth, BigDecimal sumInsured,
                               Integer termMonths, String paymentFrequency, String riskClass, Boolean smoker,
                               String occupationCode, LocalDate startDt) {
    }

    public record QuoteResult(Long productId, String productCode, int age, BigDecimal ratePer1000, BigDecimal basePremium,
                              BigDecimal annualPremium, BigDecimal installmentPremium, int installmentsPerYear,
                              String paymentFrequency, List<String> trace) {
    }

    public static int installmentsPerYear(String freq) {
        Integer n = INSTALLMENTS.get(freq);
        if (n == null) {
            throw ApiException.validation("paymentFrequency must be ANNUAL, SEMI, QUARTERLY or MONTHLY");
        }
        return n;
    }

    public QuoteResult quote(QuoteRequest r, String actor, boolean log) {
        Product p = products.findById(r.productId()).orElseThrow(() -> ApiException.notFound("Product", r.productId()));
        List<String> errors = new ArrayList<>();
        if (r.dateOfBirth() == null) {
            errors.add("dateOfBirth is required");
        }
        if (r.sumInsured() == null || r.sumInsured().signum() <= 0) {
            errors.add("sumInsured must be positive");
        }
        String freq = r.paymentFrequency() == null ? "ANNUAL" : r.paymentFrequency();
        String risk = r.riskClass() == null ? "STD" : r.riskClass();
        if (!List.of("STD", "PREF", "SUB", "HIGH").contains(risk)) {
            errors.add("riskClass must be STD, PREF, SUB or HIGH");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION, "Quote validation failed", errors);
        }
        int installments = installmentsPerYear(freq);
        LocalDate start = r.startDt() == null ? LocalDate.now() : r.startDt();
        int age = Period.between(r.dateOfBirth(), start).getYears();
        int term = r.termMonths() == null ? p.getMinTermMonths() : r.termMonths();

        List<String> eligibility = new ArrayList<>();
        if (age < p.getMinAge() || age > p.getMaxAge()) {
            eligibility.add("Age " + age + " outside product range " + p.getMinAge() + "-" + p.getMaxAge());
        }
        if (r.sumInsured().compareTo(p.getMinSumInsured()) < 0 || r.sumInsured().compareTo(p.getMaxSumInsured()) > 0) {
            eligibility.add("Sum insured outside product range " + p.getMinSumInsured() + "-" + p.getMaxSumInsured());
        }
        if (term < p.getMinTermMonths() || term > p.getMaxTermMonths()) {
            eligibility.add("Term " + term + " months outside product range " + p.getMinTermMonths() + "-" + p.getMaxTermMonths());
        }
        if (!eligibility.isEmpty()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE, "Not eligible for product " + p.getProductCode(), eligibility);
        }

        List<String> trace = new ArrayList<>();
        List<BigDecimal> rates = jdbc.queryForList(
                "SELECT rate_per_1000 FROM rate_base WHERE product_id=? AND risk_class=? AND ? BETWEEN age_from AND age_to",
                BigDecimal.class, p.getId(), risk, age);
        BigDecimal rate;
        if (rates.isEmpty()) {
            rate = p.getBaseRate();
            trace.add("No rate table row for age " + age + "/" + risk + "; using product base rate " + rate);
        } else {
            rate = rates.get(0);
            trace.add("Rate table: age " + age + ", risk " + risk + " -> " + rate + " per 1000");
        }
        BigDecimal base = r.sumInsured().divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP).multiply(rate);
        base = Money.of(base);
        trace.add("Base premium = " + r.sumInsured() + " / 1000 x " + rate + " = " + base);

        Integer occClass = null;
        if (r.occupationCode() != null) {
            List<Integer> c = jdbc.queryForList("SELECT risk_class FROM uw_occupation WHERE occ_code=?", Integer.class, r.occupationCode());
            occClass = c.isEmpty() ? null : c.get(0);
        }
        BigDecimal running = base;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal taxPct = BigDecimal.ZERO;
        for (Map<String, Object> rule : jdbc.queryForList(
                "SELECT * FROM rating_rule WHERE product_id=? AND active='Y' ORDER BY rule_order", p.getId())) {
            String field = (String) rule.get("condition_field");
            String op = (String) rule.get("condition_op");
            String value = (String) rule.get("condition_value");
            Object actual = switch (field) {
                case "SMOKER" -> Boolean.TRUE.equals(r.smoker()) ? "Y" : "N";
                case "AGE" -> age;
                case "SUM_INSURED" -> r.sumInsured();
                case "OCC_CLASS" -> occClass;
                case "RISK_CLASS" -> risk;
                case "TERM" -> term;
                case "FREQUENCY" -> freq;
                default -> "ANY";
            };
            if (!matches(actual, op, value)) {
                continue;
            }
            String kind = (String) rule.get("rule_kind");
            String amountType = (String) rule.get("amount_type");
            BigDecimal amount = (BigDecimal) rule.get("amount");
            String name = (String) rule.get("rule_name");
            if ("TAX".equals(kind)) {
                taxPct = taxPct.add(amount);
                trace.add(name + ": tax " + amount + "% (applied after fees)");
                continue;
            }
            if ("FEE".equals(kind)) {
                BigDecimal fee = "PCT".equals(amountType) ? Money.pct(running, amount) : Money.of(amount);
                fees = fees.add(fee);
                trace.add(name + ": +" + fee + " fee");
                continue;
            }
            BigDecimal delta = "PCT".equals(amountType) ? Money.pct(running, amount) : Money.of(amount);
            if ("DISCOUNT".equals(kind)) {
                delta = delta.negate();
            }
            running = running.add(delta);
            trace.add(name + ": " + (delta.signum() >= 0 ? "+" : "") + delta + " -> " + running);
        }
        BigDecimal annual = running.add(fees);
        if (taxPct.signum() > 0) {
            BigDecimal tax = Money.pct(annual, taxPct);
            annual = annual.add(tax);
            trace.add("Tax " + taxPct + "% = " + tax + " -> " + annual);
        }

        Map<String, Object> rounding = jdbc.queryForList("SELECT * FROM rounding_rule WHERE product_id=?", p.getId())
                .stream().findFirst().orElse(Map.of("scale_digits", 2, "rounding_mode", "HALF_UP", "min_premium", BigDecimal.ZERO));
        annual = round(annual, ((Number) rounding.get("scale_digits")).intValue(), (String) rounding.get("rounding_mode"));
        BigDecimal minPremium = (BigDecimal) rounding.get("min_premium");
        if (annual.compareTo(minPremium) < 0) {
            trace.add("Minimum premium " + minPremium + " applied");
            annual = minPremium;
        }
        trace.add("Annual premium (rounded " + rounding.get("rounding_mode") + ") = " + annual);

        BigDecimal factor = MODAL_FACTOR.get(freq);
        BigDecimal installment = annual.multiply(factor).divide(new BigDecimal(installments), 2, RoundingMode.HALF_UP);
        trace.add("Installment = " + annual + " x modal factor " + factor + " / " + installments + " = " + installment);

        if (log) {
            jdbc.update("INSERT INTO premium_quote_log (product_id, customer_id, input_summary, trace, annual_premium, created_by) VALUES (?,?,?,?,?,?)",
                    p.getId(), r.customerId(),
                    String.format("age=%d si=%s risk=%s freq=%s smoker=%s", age, r.sumInsured(), risk, freq, r.smoker()),
                    String.join("\n", trace), annual, actor);
        }
        return new QuoteResult(p.getId(), p.getProductCode(), age, rate, base, Money.of(annual), installment,
                installments, freq, trace);
    }

    public static boolean matches(Object actual, String op, String value) {
        if (op == null || "ANY".equals(op)) {
            return true;
        }
        if (actual == null || value == null) {
            return false;
        }
        if (actual instanceof Number || actual instanceof BigDecimal) {
            BigDecimal a = new BigDecimal(actual.toString());
            BigDecimal v = new BigDecimal(value);
            int c = a.compareTo(v);
            return switch (op) {
                case "EQ" -> c == 0;
                case "NE" -> c != 0;
                case "GT" -> c > 0;
                case "GTE" -> c >= 0;
                case "LT" -> c < 0;
                case "LTE" -> c <= 0;
                default -> false;
            };
        }
        String a = actual.toString();
        return switch (op) {
            case "EQ" -> a.equals(value);
            case "NE" -> !a.equals(value);
            case "IN" -> List.of(value.split(",")).contains(a);
            default -> false;
        };
    }

    static BigDecimal round(BigDecimal v, int scale, String mode) {
        BigDecimal r = switch (mode) {
            case "DOWN" -> v.setScale(scale, RoundingMode.DOWN);
            case "UP" -> v.setScale(scale, RoundingMode.UP);
            case "NEAREST_5" -> v.divide(new BigDecimal("5"), 0, RoundingMode.HALF_UP).multiply(new BigDecimal("5")).setScale(2);
            default -> v.setScale(scale, RoundingMode.HALF_UP);
        };
        return r.setScale(2, RoundingMode.HALF_UP);
    }
}
