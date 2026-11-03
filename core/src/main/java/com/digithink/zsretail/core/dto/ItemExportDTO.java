package com.digithink.zsretail.core.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.2, the Excel export of the store's Articles page (GET /item-catalogue/export): the items of the lines the list
 * shows for its filters, one row each, at most limit (truncated beyond). docs/admin-pages.md, Articles, "Excel export".
 */
@Data
@NoArgsConstructor
public class ItemExportDTO {
	private int limit;
	private boolean truncated;
	/** The installation keeps its own stock: the stock columns are given. */
	private boolean withStock;
	/** The cost (CMP) is given (the store's cost right, as the list). */
	private boolean withCost;
	private List<Row> rows = new ArrayList<>();

	@Data
	@NoArgsConstructor
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public static class Row {
		private Long itemId;
		private String navItemNo;
		private String itemCode;
		private String variantCode;
		private String variantDescription;
		private String name;
		private String barcode;
		private String familyName;
		private String subFamilyName;
		private String categoryCode;
		private String categoryName;
		private String sectionCode;
		private String sectionName;
		/** The selling price before VAT, its VAT % and with VAT (3 decimals). */
		private Double unitPrice;
		private Integer vatPercent;
		private Double price;
		/** In clearance, sold below it: the public price with VAT (the till's rule), else null. */
		private Double publicPrice;
		private BigDecimal stockQuantity;
		private Integer minStockLevel;
		private Double cost;
		private Double maximumAuthorizedDiscount;
		private boolean active;
		private String origin;
		private Boolean ownPrice;
		private String type;
		private String unitOfMeasure;
	}
}
