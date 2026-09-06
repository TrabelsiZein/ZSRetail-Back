package com.digithink.zsretail.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockReportRowDTO {
    private String groupLabel;
    private String itemCode;       // null when grouped by family/subfamily
    private Long currentQty;
    private Long minStockLevel;
    private Double stockValue;     // currentQty * costPrice
    private String status;         // OK / LOW / OUT
}
