package com.digithink.zsretail.core.erp.engine;

import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.ARGENTE;
import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.FRANCHISE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * Item variants (ZEN), step 5: the clearance of a point de stock row (ho_stock_point_item.clearance, clearance_price),
 * written and compared by the items run ({@link CatalogueTestBed}). The rows of before (no clearance: what
 * HoStockPointRows reads from a null column, HoStockPointRowsTest) are not changed by an ERP without clearance; the
 * price of the row and the item are untouched.
 */
class CatalogueClearanceTest {

	private static final long ARGENTE_ID = 1;
	private static final long FRANCHISE_ID = 2;

	private CatalogueTestBed bed;

	@BeforeEach
	void setUp() {
		bed = new CatalogueTestBed();
	}

	@Test
	@DisplayName("After the upgrade, rows of before and an ERP without clearance (Happyness): no row changed")
	void rowsOfBeforeNotChanged() {
		ErpItemDTO bike = item("1000", null, null);
		bed.items.put(ARGENTE, new ArrayList<>(Collections.singletonList(bike)));
		bed.items.put(FRANCHISE, new ArrayList<>(Collections.singletonList(bike)));
		bed.engine.items();
		// The rows as a head office of before has them: no clearance (the 7-argument row, a null column read as false)
		for (Map<String, StockPoints.Row> ofPoint : bed.points.rows.values()) {
			ofPoint.replaceAll((code, row) -> new StockPoints.Row(row.itemCode, row.name, row.description, row.familyCode,
					row.subFamilyCode, row.unitPrice, row.active));
		}
		int writes = bed.points.writes;

		CatalogueRun<ErpItemDTO> run = bed.engine.items();

		for (String point : new String[] { ARGENTE, FRANCHISE }) {
			assertEquals(0, pointSummary(run, point).get("changed"), point);
			assertEquals(0, pointSummary(run, point).get("written"), point);
		}
		assertEquals(writes, bed.points.writes);
		assertEquals(0, run.count("changed"));
	}

	@Test
	@DisplayName("A row in clearance (true, 7.000) is created with both values; the other point's row without")
	void clearanceRowCreated() {
		bed.items.put(ARGENTE, new ArrayList<>(Collections.singletonList(item("14ZDU05~001", false, BigDecimal.ZERO))));
		bed.items.put(FRANCHISE,
				new ArrayList<>(Collections.singletonList(item("14ZDU05~001", true, new BigDecimal("7.000")))));

		bed.engine.items();

		StockPoints.Row franchise = bed.points.rows(FRANCHISE_ID).get("14ZDU05~001");
		assertTrue(franchise.clearance);
		assertEquals(0, new BigDecimal("7.000").compareTo(franchise.clearancePrice));
		assertEquals(19.5, franchise.unitPrice, "the price of the row is the ERP's public price, as before");
		StockPoints.Row argente = bed.points.rows(ARGENTE_ID).get("14ZDU05~001");
		assertFalse(argente.clearance);
		assertEquals(0, BigDecimal.ZERO.compareTo(argente.clearancePrice));
		assertEquals(19.5, bed.tables.itemByCode("14ZDU05~001").get().getUnitPrice(), "the item's price untouched");
	}

	@Test
	@DisplayName("Clearance true to false at one point: that row changed and written, the other point and the item not")
	void clearanceFlipChangesTheRow() {
		ErpItemDTO inFranchise = item("14ZDU05~001", true, new BigDecimal("7.000"));
		bed.items.put(ARGENTE, new ArrayList<>(Collections.singletonList(item("14ZDU05~001", false, null))));
		bed.items.put(FRANCHISE, new ArrayList<>(Collections.singletonList(inFranchise)));
		bed.engine.items();
		inFranchise.setClearance(Boolean.FALSE);

		CatalogueRun<ErpItemDTO> run = bed.engine.items();

		assertEquals(1, pointSummary(run, FRANCHISE).get("changed"));
		assertEquals(1, pointSummary(run, FRANCHISE).get("written"));
		assertEquals(0, pointSummary(run, ARGENTE).get("changed"));
		assertEquals(0, run.count("changed"), "the item does not compare the clearance");
		assertFalse(bed.points.rows(FRANCHISE_ID).get("14ZDU05~001").clearance);
		assertNull(bed.points.rows(ARGENTE_ID).get("14ZDU05~001").clearancePrice);
	}

	@Test
	@DisplayName("Same price and clearance price (7 against 7.000): no change; 7.001 is a change; before VAT at 5"
			+ " decimals (ZEN): 5.88235 against 5.882350 none, 5.88236 a change")
	void samePricesNoChange() {
		ErpItemDTO inFranchise = item("14ZDU05~001", true, new BigDecimal("7.000"));
		bed.items.put(FRANCHISE, new ArrayList<>(Collections.singletonList(inFranchise)));
		bed.engine.items();

		inFranchise.setClearancePrice(new BigDecimal("7"));
		inFranchise.setUnitPrice(new BigDecimal("19.500"));
		CatalogueRun<ErpItemDTO> same = bed.engine.items();
		assertEquals(0, pointSummary(same, FRANCHISE).get("changed"));
		assertEquals(0, pointSummary(same, FRANCHISE).get("written"));

		inFranchise.setClearancePrice(new BigDecimal("7.001"));
		CatalogueRun<ErpItemDTO> other = bed.engine.items();
		assertEquals(1, pointSummary(other, FRANCHISE).get("changed"));
		assertEquals(0, new BigDecimal("7.001").compareTo(bed.points.rows(FRANCHISE_ID).get("14ZDU05~001").clearancePrice));

		inFranchise.setClearancePrice(new BigDecimal("5.88235"));
		bed.engine.items();
		inFranchise.setClearancePrice(new BigDecimal("5.882350"));
		assertEquals(0, pointSummary(bed.engine.items(), FRANCHISE).get("changed"));
		inFranchise.setClearancePrice(new BigDecimal("5.88236"));
		assertEquals(1, pointSummary(bed.engine.items(), FRANCHISE).get("changed"), "a change below the millime");
		assertEquals(0,
				new BigDecimal("5.88236").compareTo(bed.points.rows(FRANCHISE_ID).get("14ZDU05~001").clearancePrice));
	}

	/** An item of the ERP at one point, with its clearance as the point gives it (null: an ERP without clearance). */
	private static ErpItemDTO item(String code, Boolean clearance, BigDecimal clearancePrice) {
		ErpItemDTO item = new ErpItemDTO();
		item.setExternalId(code);
		item.setCode(code);
		item.setName("CRAVATE");
		item.setUnitPrice(new BigDecimal("19.5"));
		item.setDefaultVAT(19);
		item.setActive(Boolean.TRUE);
		item.setClearance(clearance);
		item.setClearancePrice(clearancePrice);
		return item;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> pointSummary(CatalogueRun<ErpItemDTO> run, String pointCode) {
		return (Map<String, Object>) ((Map<String, Object>) run.getSummary().get("stockPoints")).get(pointCode);
	}
}
