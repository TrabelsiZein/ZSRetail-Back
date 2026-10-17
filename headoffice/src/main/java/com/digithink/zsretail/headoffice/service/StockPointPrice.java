package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;

import com.digithink.zsretail.headoffice.model.HoStockPointItem;

/**
 * Item variants (ZEN), clearance (2026-10-17, Q1 of docs/variants-zen-analysis-2026-10-14.md): the selling price of an
 * item at a point de stock, the one rule of the head office. The point's row in clearance with a clearance price above
 * 0 sells at its clearance price; any other row (clearance false or null, a clearance price null or 0) at its own price
 * as before. Both prices are before VAT: the item's VAT applies to either. Used by the copies to the stores
 * ({@link HoCatalogueService#pointCopy}), so a store sells the price it receives, and by the head office's pages (Items by
 * point de stock, the NAV items grid). EMTOP and Happyness rows have no clearance: their price is the row's.
 */
public final class StockPointPrice {

	private StockPointPrice() {
	}

	/** True when the row sells at its clearance price: clearance true and a clearance price above 0. */
	public static boolean clearanceApplies(Boolean clearance, BigDecimal clearancePrice) {
		return Boolean.TRUE.equals(clearance) && clearancePrice != null && clearancePrice.signum() > 0;
	}

	/** The selling price before VAT: the clearance price when it applies, else the row's price (null stays null). */
	public static Double sellingPrice(Double unitPrice, Boolean clearance, BigDecimal clearancePrice) {
		return clearanceApplies(clearance, clearancePrice) ? Double.valueOf(clearancePrice.doubleValue()) : unitPrice;
	}

	/** The selling price of a point's row; null for no row. */
	public static Double sellingPrice(HoStockPointItem row) {
		return row == null ? null : sellingPrice(row.getUnitPrice(), row.getClearance(), row.getClearancePrice());
	}

	/** True when this row of a point sells at its clearance price; false for no row. */
	public static boolean clearanceApplies(HoStockPointItem row) {
		return row != null && clearanceApplies(row.getClearance(), row.getClearancePrice());
	}
}
