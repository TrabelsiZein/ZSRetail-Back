package com.digithink.zsretail.dto.report;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockReportRowDTO {
    private String groupLabel;
    private String itemCode;       // null when grouped by family/subfamily
    private BigDecimal currentQty; // 2.2.1: with its decimals
    private Long minStockLevel;
    private Double stockValue;     // currentQty * costPrice
    private String status;         // OK / LOW / OUT
}
