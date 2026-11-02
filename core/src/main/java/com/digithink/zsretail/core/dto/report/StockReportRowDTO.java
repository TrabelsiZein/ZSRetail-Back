package com.digithink.zsretail.core.dto.report;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StockReportRowDTO {
    private String groupLabel;
    private String itemCode;       // null when grouped by family/subfamily
    private BigDecimal currentQty; // 2.2.1: with its decimals (3.1.2: at the date asked)
    private Long minStockLevel;
    /**
     * currentQty * costPrice. 3.1.2: null for an item without cost (no CMP, a stock other than 0), never 0; a group row:
     * the sum of its items with a cost.
     */
    private Double stockValue;
    private String status;         // OK / LOW / OUT / NEGATIVE

    /** 3.1.2: the items of the row without cost (no CMP, a stock other than 0); absent when none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long itemsWithoutCost;

    /** 3.1.2, a past date: the items of the row valued at their current CMP (no cost history at that date); absent when none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long itemsAtCurrentCmp;

    public StockReportRowDTO(String groupLabel, String itemCode, BigDecimal currentQty, Long minStockLevel,
            Double stockValue, String status) {
        this.groupLabel = groupLabel;
        this.itemCode = itemCode;
        this.currentQty = currentQty;
        this.minStockLevel = minStockLevel;
        this.stockValue = stockValue;
        this.status = status;
    }
}
