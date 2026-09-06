package com.digithink.zsretail.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalesReportRowDTO {
    private String groupLabel;
    private Long nbTransactions;
    private Long totalQuantity;
    private Double totalHt;
    private Double totalVat;
    private Double totalTtc;
    private Double totalDiscount;
}
