package com.digithink.zsretail.core.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.digithink.zsretail.core.utils.Quantities;

/**
 * The value helpers of the reports (dates of a range, the columns of a JPQL row). Code cleanup step 5: were the private
 * helpers of ReportService, now used by the stock reports (ReportService, core) and the store's reports
 * (SalesReportService).
 */
public final class ReportValues {

    private ReportValues() {
    }

    public static LocalDateTime toFrom(LocalDate d) {
        return d != null ? d.atStartOfDay() : LocalDateTime.of(2000, 1, 1, 0, 0);
    }

    public static LocalDateTime toTo(LocalDate d) {
        return d != null ? d.atTime(LocalTime.MAX) : LocalDateTime.now();
    }

    public static String str(Object o) { return o != null ? o.toString() : ""; }

    public static Long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Long)    return (Long) o;
        if (o instanceof Integer) return ((Integer) o).longValue();
        if (o instanceof Number)  return Quantities.wholeLongOrFail(o, "Report"); // 2.2.1: a decimal quantity sum is loud, never truncated
        return 0L;
    }

    /** 2.2.1: a quantity (or a sum of quantities) with its decimals, without trailing zeros; null as 0. */
    public static BigDecimal toQuantity(Object o) {
        if (o == null) return BigDecimal.ZERO;
        BigDecimal value = o instanceof BigDecimal ? (BigDecimal) o : new BigDecimal(o.toString());
        return Quantities.normalize(value);
    }

    public static Double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Double) return (Double) o;
        if (o instanceof Number) return ((Number) o).doubleValue();
        return 0.0;
    }
}
