package com.digithink.zsretail.core.dto.report;

import java.util.LinkedHashSet;
import java.util.Set;

import com.digithink.zsretail.core.model.enumeration.StockMovementType;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.2, the stock movements report's filters (GET /report/stock-movements), all optional: the item's code or name
 * contains search (case ignored), its family, sub-family, category or section, and the movement types (any of them).
 * docs/modules/reporting.md, "Stock movements report".
 */
@Data
@NoArgsConstructor
public class StockMovementReportFilter implements ReportItemFilter {

    private String search;
    private Long familyId;
    private Long subFamilyId;
    private String categoryCode;
    private String sectionCode;
    /** Empty: every type (COST_ADJUSTMENT never: it moves no stock). */
    private Set<StockMovementType> types = new LinkedHashSet<>();

    /** True when a filter needs the item's columns (the items query joined). */
    public boolean onItem() {
        return search != null || familyId != null || subFamilyId != null || categoryCode != null || sectionCode != null;
    }
}
