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
