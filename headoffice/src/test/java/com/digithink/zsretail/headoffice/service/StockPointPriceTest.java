package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.headoffice.model.HoStockPointItem;

/**
 * Item variants (ZEN), clearance (2026-10-17): the one rule of a point de stock row's selling price. The clearance price
 * only when clearance is true and the price is above 0; any other row sells at its own price, as before.
 */
class StockPointPriceTest {

	private static HoStockPointItem row(double unitPrice, Boolean clearance, String clearancePrice) {
		HoStockPointItem row = new HoStockPointItem();
		row.setUnitPrice(unitPrice);
		row.setClearance(clearance);
		row.setClearancePrice(clearancePrice == null ? null : new BigDecimal(clearancePrice));
		return row;
	}

	@Test
	@DisplayName("Clearance true and a clearance price above 0: the clearance price (before VAT, as the row's price)")
	void clearanceWithAPrice() {
		HoStockPointItem tie = row(19.5, true, "7.000");
		assertTrue(StockPointPrice.clearanceApplies(tie));
		assertEquals(7.0, StockPointPrice.sellingPrice(tie));
		assertEquals(0.001, StockPointPrice.sellingPrice(row(19.5, true, "0.001")), "any price above 0");
	}

	@Test
	@DisplayName("Clearance true and a clearance price of 0: the public price")
	void clearanceAtZero() {
		HoStockPointItem tie = row(19.5, true, "0.000");
		assertFalse(StockPointPrice.clearanceApplies(tie));
		assertEquals(19.5, StockPointPrice.sellingPrice(tie));
		assertEquals(19.5, StockPointPrice.sellingPrice(row(19.5, true, "-1")), "below 0: the public price too");
	}

	@Test
	@DisplayName("Clearance true and no clearance price: the public price")
	void clearanceWithoutPrice() {
		HoStockPointItem tie = row(19.5, true, null);
		assertFalse(StockPointPrice.clearanceApplies(tie));
		assertEquals(19.5, StockPointPrice.sellingPrice(tie));
	}

	@Test
	@DisplayName("Clearance false, with a clearance price: the public price")
	void noClearance() {
		HoStockPointItem tie = row(19.5, false, "7.000");
		assertFalse(StockPointPrice.clearanceApplies(tie));
		assertEquals(19.5, StockPointPrice.sellingPrice(tie));
	}

	@Test
	@DisplayName("A Happyness row (clearance and clearance price null, as every row before 3.1.0): its own price")
	void happynessRow() {
		HoStockPointItem bike = row(100.0, null, null);
		assertFalse(StockPointPrice.clearanceApplies(bike));
		assertEquals(100.0, StockPointPrice.sellingPrice(bike));
		assertNull(StockPointPrice.sellingPrice(null), "no row: no price");
		assertFalse(StockPointPrice.clearanceApplies((HoStockPointItem) null));
		assertNull(StockPointPrice.sellingPrice(null, true, new BigDecimal("0")), "a null price stays null");
	}
}
