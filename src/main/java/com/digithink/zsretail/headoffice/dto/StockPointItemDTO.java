package com.digithink.zsretail.headoffice.dto;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stock points, step 4: one row of a point as the page "Items by point de stock" reads it (read only). Name,
 * description, family, sub-family, price and active come from the row; the VAT, the barcodes and the other fields from
 * the head office item.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class StockPointItemDTO {

	/** ho_stock_point_item.id. */
	private Long id;
	private Long stockPointId;
	private String stockPointCode;
	private String stockPointName;

	// From the row
	private Long itemId;
	private String itemCode;
	private String name;
	private String description;
	private String familyCode;
	private String familyName;
	private String subFamilyCode;
	private String subFamilyName;
	/** Before VAT (the meaning of item.unitPrice). */
	private Double unitPrice;
	private Boolean active;

	// From the item
	private Integer defaultVAT;
	private String type;
	private String unitOfMeasure;
	private String category;
	private String brand;
	private String itemDiscGroup;
	private Double maximumAuthorizedDiscount;
	private Boolean showInPos;
	private Boolean itemActive;
	private List<Barcode> barcodes = new ArrayList<>();

	/** A barcode of the item. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Barcode {
		private String barcode;
		private Boolean isPrimary;
		private String description;
		private Boolean active;
	}
}
