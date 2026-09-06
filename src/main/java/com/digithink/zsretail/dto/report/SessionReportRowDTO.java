package com.digithink.zsretail.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionReportRowDTO {
    private String groupLabel;
    private Long nbSessions;
    private Long nbTransactions;
    private Double totalAmount;
    private Double avgBasket;
}
