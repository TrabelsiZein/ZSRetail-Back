package com.digithink.zsretail.dto.report;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockMovementReportRowDTO {
    private String groupLabel;
    private BigDecimal qtyIn; // 2.2.1: with their decimals
    private BigDecimal qtyOut;
    private BigDecimal netQty;       // qtyIn - qtyOut
    private Long nbMovements;
}
