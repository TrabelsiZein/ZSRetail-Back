package com.digithink.zsretail.dto;

import java.util.List;

import lombok.Data;

@Data
public class ProcessPurchaseRequestDTO {

	private Long vendorId;

	private List<PurchaseLineDTO> lines;

	private String notes;

	/** Optional vendor delivery note / BL number. */
	private String vendorBlNumber;

	@Data
	public static class PurchaseLineDTO {
		private Long itemId;
		private Integer quantity;
		private Double unitPrice;
		/** Discount percentage (e.g. 10.0 = 10%). Null or 0 means no discount. */
		private Double discountPercent;
		/** VAT rate for this line (e.g. 19). If null, defaults to item's defaultVAT. */
		private Integer vatPercent;
	}
}
