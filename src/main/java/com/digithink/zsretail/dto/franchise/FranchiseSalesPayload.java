package com.digithink.zsretail.dto.franchise;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Sales data pushed by a franchise client to the admin.
 * Stored in FranchiseSalesHeader / FranchiseSalesLine for tracking and dashboards.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FranchiseSalesPayload {

	private String locationCode;
	private String externalSalesNumber;

	@JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
	private LocalDateTime salesDate;

	private String customerName;
	private String cashierName;
	private Double totalHT;
	private Double totalTVA;
	private Double totalTTC;

	private List<LinePayload> lines;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class LinePayload {
		private String itemCode;
		private String itemName;
		private Integer quantity;
		private Double unitPrice;
		private Double discountAmount;
		private Double totalAmount;
	}
}
