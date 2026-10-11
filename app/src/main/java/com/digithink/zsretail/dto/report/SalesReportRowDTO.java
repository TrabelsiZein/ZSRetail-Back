package com.digithink.zsretail.dto.report;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalesReportRowDTO {
    private String groupLabel;
    private Long nbTransactions;
    private BigDecimal totalQuantity; // 2.2.1: with its decimals (0.2 L sold), written without trailing zeros
    private Double totalHt;
    private Double totalVat;
    private Double totalTtc;
    private Double totalDiscount;
}
