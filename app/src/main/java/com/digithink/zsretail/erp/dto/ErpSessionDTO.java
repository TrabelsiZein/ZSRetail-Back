package com.digithink.zsretail.erp.dto;

import lombok.Data;

@Data
public class ErpSessionDTO {

//	private String cashierExternalId; // Cashier user external ID from ERP
//	private String cashierUsername;
//	private LocalDateTime openedAt;
//	private LocalDateTime closedAt;
//	private String status; // OPENED, CLOSED, TERMINATED
//	private BigDecimal openingCash;
//	private BigDecimal realCash;
//	private BigDecimal posUserClosureCash;
//	private BigDecimal responsibleClosureCash;
//	private String verifiedByUsername; // Username of user who verified
//	private LocalDateTime verifiedAt;
//	private String verificationNotes;
//
//	// Additional fields for ERP export
//	private String responsibilityCenter;
//	private String locationExternalId;

	private String sessionNumber;
	private String locationCode;
	private Integer ticketCount;
	private Double closingAmount;
	private Integer returnCount;
	private Integer returnCashedCount;
	private Double returnCashedAmount;

}
