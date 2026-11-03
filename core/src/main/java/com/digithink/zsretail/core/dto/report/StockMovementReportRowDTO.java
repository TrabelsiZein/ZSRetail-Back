package com.digithink.zsretail.core.dto.report;

import java.math.BigDecimal;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StockMovementReportRowDTO {
    /**
     * The row: an item's name, a family / sub-family / category / section name ("No Family"... without one), a type, a
     * day yyyy-MM-dd or a month yyyy-MM (3.1.2: zero-padded, they sort as text).
     */
    private String groupLabel;
    private BigDecimal qtyIn; // 2.2.1: with their decimals
    private BigDecimal qtyOut;
    private BigDecimal netQty;       // qtyIn - qtyOut
    private Long nbMovements;

    /** 3.1.2: the distinct items moved in the row (an item row: 1). */
    private long itemCount;
    /**
     * 3.1.2, a group row: what the page filters on to open it (drill-down): the family's or sub-family's id, the
     * category's or section's code, the type, the day yyyy-MM-dd, the month yyyy-MM; null for the row of the items without
     * one. A sub-family row also carries familyName (sub-family names repeat across families).
     */
    private String groupKey;
    /**
     * 3.1.2, an item row only: its id (the page opens its stock card), code and family name (null when none). Before
     * 3.1.2 the label was "code - name".
     */
    private Long itemId;
    private String itemCode;
    private String familyName;

    public StockMovementReportRowDTO(String groupLabel, BigDecimal qtyIn, BigDecimal qtyOut, BigDecimal netQty,
            Long nbMovements) {
        this.groupLabel = groupLabel;
        this.qtyIn = qtyIn;
        this.qtyOut = qtyOut;
        this.netQty = netQty;
        this.nbMovements = nbMovements;
    }
}
