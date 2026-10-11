package com.digithink.zsretail.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseReportRowDTO {
    private String groupLabel;
    private Long nbPurchases;
    private Long totalQuantity;
    private Double totalHt;
    private Double totalVat;
    private Double totalTtc;
}
