package com.digithink.zsretail.erp.dto;

import java.time.LocalDate;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for payment header synchronization with ERP
 */
@Data
@NoArgsConstructor
public class ErpPaymentHeaderDTO {

	private String paymentClass;
	private LocalDate postDate;
}

