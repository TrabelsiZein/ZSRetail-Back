package com.digithink.zsretail.core.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Item variants (ZEN), step 11a: the variants of one NAV item number (a line of the "NAV items" page, expanded). On a
 * head office, the points de stock that have a row of one of them (list order) and each variant's row per point: the
 * variant × point de stock price grid. On a store stockPoints is null and each variant has its own unit price.
 */
@Data
@NoArgsConstructor
public class NavItemVariantsDTO {

	/** Head office only: the columns of the price grid; null on a store. */
	private List<Point> stockPoints;

	private List<Variant> variants = new ArrayList<>();

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Point {
		private Long id;
		private String code;
		private String name;
	}

	/** The row of one variant in one point de stock (ho_stock_point_item). */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class PointPrice {
		private Double unitPrice;
		private boolean active;
		private boolean clearance;
		private BigDecimal clearancePrice;
		/**
		 * Item variants (ZEN), clearance (2026-10-17): the row sells at its clearance price (the head office's rule,
		 * headoffice StockPointPrice: clearance true and a clearance price above 0).
		 */
		private boolean clearanceApplies;
		/** The price a store of this point sells at, before VAT: the clearance price when it applies, else unitPrice. */
		private Double sellingPrice;

		/** Without the head office's rule applied (clearanceApplies false, sellingPrice null). */
		public PointPrice(Double unitPrice, boolean active, boolean clearance, BigDecimal clearancePrice) {
			this(unitPrice, active, clearance, clearancePrice, false, null);
		}
	}

	@Data
	@NoArgsConstructor
	public static class Variant {
		private Long id;
		private String itemCode;
		private String variantCode;
		private String variantDescription;
		private String sizeCode;
		private String sizeDescription;
		private String colorCode;
		private String colorDescription;
		private String choiceCode;
		private String choiceDescription;
		private boolean active;
		/** The item's own price before VAT (the one a store sells at). */
		private Double unitPrice;
		/** The VAT rate of the prices (percent), as item.defaultVAT. */
		private Integer defaultVAT;
		/**
		 * Item variants (ZEN), clearance (2026-10-17), a store only: item.clearance, item.publicPrice (before VAT) and
		 * item.ownPrice, as the till receives them; the page strikes publicPrice with the till's rule (front
		 * clearanceShown). Null on a head office (its prices are per point) and for the other customers.
		 */
		private Boolean clearance;
		private Double publicPrice;
		private Boolean ownPrice;
		/** Null where the installation keeps no stock of its own. */
		private BigDecimal stockQuantity;
		/** Head office only: point de stock id to the variant's row there (no entry: no row); null on a store. */
		private Map<Long, PointPrice> prices;

		public void putPrice(Long pointId, PointPrice price) {
			if (prices == null) {
				prices = new LinkedHashMap<>();
			}
			prices.put(pointId, price);
		}
	}
}
