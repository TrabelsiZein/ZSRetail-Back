package com.digithink.zsretail.erp.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for payment line synchronization with ERP
 */
@Data
@NoArgsConstructor
public class ErpPaymentLineDTO {

	private String docNo;
	private String custNo;
	private BigDecimal amount;
	private String fenceNo;
	private String ticketNo;
	private String titleNo;
	private LocalDate dueDate;
	private String drawerName;
}

