package com.digithink.zsretail.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for creating a product without an ERP.
 */
@Data
@NoArgsConstructor
public class QuickProductRequestDTO {

	private String name;
	private String itemCode;
	private Double unitPrice;
	private String barcode;
}
