package com.digithink.zsretail.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyReportRowDTO {
    private String groupLabel;
    private Long pointsEarned;
    private Long pointsRedeemed;
    private Long pointsAdjusted;
    private Long pointsReversed;
    private Long nbTransactions;
}
