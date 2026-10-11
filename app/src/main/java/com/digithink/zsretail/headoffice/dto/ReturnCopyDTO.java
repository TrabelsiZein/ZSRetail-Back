package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Copy of a store return (task 2.2), sent to POST /ho/sales/returns. Business codes, never a database id; no store
 * code (the authenticated store). The voucher fields are those written when the return is made; its later use is not
 * part of the copy.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReturnCopyDTO {

	private String returnNumber;

	private LocalDateTime returnDate;

	/** TransactionStatus name (COMPLETED). */
	private String status;

	/** SIMPLE_RETURN or RETURN_VOUCHER. */
	private String returnType;

	/** Sales number of the returned ticket. */
	private String originalSalesNumber;

	private Double totalReturnAmount;

	private Double discountPercentage;

	private String cashierLogin;

	private String cashierName;

	private String sessionNumber;

	private String voucherNumber;

	private Double voucherAmount;

	private LocalDate voucherExpiryDate;

	private String notes;

	private List<ReturnLineCopyDTO> lines = new ArrayList<>();
}
