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

    /*
     * 3.1.2, the rebuilt report: values without VAT (HT) and with VAT (Ttc), the VAT of each item its defaultVAT (none:
     * 0, as at the till); computed per item, the row rounded to 3 decimals. An item row only: its prices.
     */

    /** The CMP of the value (HT; at the date asked, the one the value used); null when none. Item row only. */
    private Double costPrice;
    private Double costPriceTtc;
    /** Item.unitPrice, the selling price without VAT; null when none. Item row only. */
    private Double unitPrice;
    private Double unitPriceTtc;
    /** stockValue with VAT, null in the same case. */
    private Double stockValueTtc;
    /**
     * currentQty x unitPrice (HT), today's price even at a past date; null for an item without price (a stock other
     * than 0), never 0; a group row: the sum of its items with a price.
     */
    private Double sellingValue;
    private Double sellingValueTtc;

    /** The items of the row without selling price (none, a stock other than 0); absent when none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long itemsWithoutPrice;

    /** The items of the row (an item row: 1), and among them those LOW and those OUT or NEGATIVE, by their own status. */
    private long itemCount;
    private long itemsLow;
    private long itemsOut;

    /** Item row only: its family and sub-family names; null when none. */
    private String familyName;
    private String subFamilyName;

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
