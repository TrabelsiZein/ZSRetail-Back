package com.digithink.zsretail.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockMovementReportRowDTO {
    private String groupLabel;
    private Long qtyIn;
    private Long qtyOut;
    private Long netQty;       // qtyIn - qtyOut
    private Long nbMovements;
}
