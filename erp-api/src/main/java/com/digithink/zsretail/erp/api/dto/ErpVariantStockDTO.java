package com.digithink.zsretail.erp.api.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 3.1.1 (store orders): the ERP's stock of one variant at one location (ErpConnector.fetchVariantStock). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErpVariantStockDTO {

	private String itemNo;

	/** Empty for an item without variants. */
	private String variantCode;

	private BigDecimal quantity;
}
