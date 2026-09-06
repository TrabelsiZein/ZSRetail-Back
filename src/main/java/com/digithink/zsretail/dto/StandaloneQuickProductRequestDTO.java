package com.digithink.zsretail.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for creating a product in standalone mode (no ERP).
 */
@Data
@NoArgsConstructor
public class StandaloneQuickProductRequestDTO {

	private String name;
	private String itemCode;
	private Double unitPrice;
	private String barcode;
}
