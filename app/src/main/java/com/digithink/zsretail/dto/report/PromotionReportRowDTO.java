package com.digithink.zsretail.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PromotionReportRowDTO {
    private String promotionCode;
    private String promotionName;
    private String promotionType;
    /** Number of distinct completed tickets where this promotion was applied (line or header level). */
    private Long nbTickets;
    /** Sum of all discount amounts attributed to this promotion. */
    private Double totalDiscount;
    /** Sum of pre-discount revenue (lineTotalTTC + discountAmount) influenced by this promotion. */
    private Double revenueInfluenced;
}
