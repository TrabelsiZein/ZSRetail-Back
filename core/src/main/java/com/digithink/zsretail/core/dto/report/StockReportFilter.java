package com.digithink.zsretail.core.dto.report;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.2, the stock report's filters (GET /report/stock), all optional: the item's code or name contains search (case
 * ignored), its family, sub-family, category or section, and its own status at the date (any of statuses, before the
 * grouping). docs/modules/reporting.md, "Stock report".
 */
@Data
@NoArgsConstructor
public class StockReportFilter {

    /** No CMP and a stock other than 0 (an item of any of the other four statuses may also be without cost). */
    public static final String WITHOUT_COST = "WITHOUT_COST";

    public static final List<String> STATUSES = Arrays.asList("OK", "LOW", "OUT", "NEGATIVE", WITHOUT_COST);

    /** belowMinStock=true, since 3.1.2: these three statuses of the item. */
    public static final List<String> BELOW_MIN = Arrays.asList("LOW", "OUT", "NEGATIVE");

    private String search;
    private Long familyId;
    private Long subFamilyId;
    private String categoryCode;
    private String sectionCode;
    /** Empty: every status. */
    private Set<String> statuses = new LinkedHashSet<>();

    /** The filter of before 3.1.2: below the minimum only, or none. */
    public static StockReportFilter belowMin(Boolean belowMinStock) {
        StockReportFilter filter = new StockReportFilter();
        if (Boolean.TRUE.equals(belowMinStock)) {
            filter.getStatuses().addAll(BELOW_MIN);
        }
        return filter;
    }
}
