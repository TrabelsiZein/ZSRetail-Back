package com.digithink.zsretail.core.erp.engine;

import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.ARGENTE;
import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.FRANCHISE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * Item variants (ZEN), step 6: the items run of a connector that reads changes only ({@link CatalogueTestBed} with
 * changesOnly: the fake ERP answers the rows at or after the checkpoint, as the connector filters them). The
 * checkpoint of each point de stock, nothing deactivated by absence, an item active while one of its rows is, a failed
 * point keeping its checkpoint, only the stored rows of the codes answered loaded; and the same scenario in both modes.
 */
class CatalogueChangesOnlyTest {

	private static final long ARGENTE_ID = 1;
	private static final long FRANCHISE_ID = 2;
	private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-10-11T16:53:37.137Z");
	private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-10-11T16:53:37.150Z");
	private static final OffsetDateTime T3 = OffsetDateTime.parse("2026-10-12T09:00:00Z");
	private static final OffsetDateTime T4 = OffsetDateTime.parse("2026-10-13T09:00:00Z");
	private static final OffsetDateTime T5 = OffsetDateTime.parse("2026-10-14T09:00:00Z");

	private CatalogueTestBed bed;
	/** The ERP's rows, one per (point, variant), so that a point can change alone. */
	private ErpItemDTO argente001;
	private ErpItemDTO argente002;
	private ErpItemDTO franchise001;
	private ErpItemDTO franchise002;

	@BeforeEach
	void setUp() {
		bed = changesOnlyBed(true);
		argente001 = bed.items.get(ARGENTE).get(0);
		argente002 = bed.items.get(ARGENTE).get(1);
		franchise001 = bed.items.get(FRANCHISE).get(0);
		franchise002 = bed.items.get(FRANCHISE).get(1);
	}

	/** Two variants of 70061 in both points, 001 at T1 and 002 at T2. */
	private static CatalogueTestBed changesOnlyBed(boolean changesOnly) {
		CatalogueTestBed bed = new CatalogueTestBed();
		bed.changesOnly = changesOnly;
		bed.items.put(ARGENTE, new ArrayList<>(Arrays.asList(row("70061~001", T1), row("70061~002", T2))));
		bed.items.put(FRANCHISE, new ArrayList<>(Arrays.asList(row("70061~001", T1), row("70061~002", T2))));
		return bed;
	}

	@Test
	@DisplayName("First run, no checkpoint: a whole read, every row applied, each point's checkpoint = its highest time")
	void firstRunWholeReadAndCheckpoint() {
		CatalogueRun<ErpItemDTO> run = bed.engine.items();

		assertEquals(Arrays.asList("ARGENTE since null", "FRANCHISE since null"), bed.sinceAsked);
		assertEquals(Boolean.TRUE, run.getSummary().get("changesOnly"));
		assertEquals(2, run.count("new"));
		assertEquals(2, bed.points.stored(ARGENTE_ID).size());
		assertEquals(2, bed.points.stored(FRANCHISE_ID).size());
		assertEquals(T2, bed.engine.checkpoint(ARGENTE_ID));
		assertEquals(T2, bed.engine.checkpoint(FRANCHISE_ID));
		Map<String, Object> argente = pointSummary(run, ARGENTE);
		assertNull(argente.get("since"));
		assertEquals(2, argente.get("read"));
		assertEquals("2026-10-11T16:53:37.15Z", argente.get("maxSeen"));
		assertEquals("2026-10-11T16:53:37.15Z", argente.get("checkpoint"));
		assertEquals(Boolean.TRUE, argente.get("checkpointWritten"));
		assertEquals(2, argente.get("new"));
		assertEquals(2, argente.get("written"));
		// Rule 5: only the stored rows of the codes answered, never every row of a point
		assertEquals(0, bed.points.wholeLoads);
		assertEquals(Arrays.asList("1:70061~001,70061~002", "2:70061~001,70061~002"), bed.points.codeLoads);

		bed.engine.resetCheckpoint(ARGENTE_ID);
		assertNull(bed.engine.checkpoint(ARGENTE_ID));
		bed.engine.items();
		assertEquals(Arrays.asList("ARGENTE since null", "FRANCHISE since 2026-10-11T16:53:37.150Z"),
				bed.sinceAsked.subList(2, 4));
	}

	@Test
	@DisplayName("Second run: only the changed row of 001 at ARGENTE applied; every other row and item untouched")
	void secondRunOneChangedRow() {
		bed.engine.items();
		StockPoints.Row franchiseRow = bed.points.stored(FRANCHISE_ID).get("70061~001");
		StockPoints.Row argenteRow002 = bed.points.stored(ARGENTE_ID).get("70061~002");
		int saves = bed.tables.itemSaves;
		argente001.setUnitPrice(new BigDecimal("55"));
		argente001.setColorDescription("Blanc");
		argente001.setLastModifiedAt(T3);

		CatalogueRun<ErpItemDTO> run = bed.engine.items();

		assertEquals(Arrays.asList("ARGENTE since " + T2, "FRANCHISE since " + T2), bed.sinceAsked.subList(2, 4));
		// The rows at the checkpoint itself are answered again (at or after): unchanged, not written
		assertEquals(2, pointSummary(run, ARGENTE).get("read"));
		assertEquals(1, pointSummary(run, FRANCHISE).get("read"));
		assertEquals(0, run.count("new"));
		assertEquals(1, run.count("changed"));
		assertEquals(Arrays.asList("70061~001"), codes(run));
		assertEquals(saves + 1, bed.tables.itemSaves, "only 001 saved");
		Item small = item("70061~001");
		assertEquals(55.0, small.getUnitPrice());
		assertEquals("Blanc", small.getColorDescription());
		assertEquals(1, pointSummary(run, ARGENTE).get("written"));
		assertEquals(0, pointSummary(run, FRANCHISE).get("written"));
		assertEquals(55.0, bed.points.stored(ARGENTE_ID).get("70061~001").unitPrice);
		assertSame(franchiseRow, bed.points.stored(FRANCHISE_ID).get("70061~001"), "FRANCHISE's row untouched");
		assertSame(argenteRow002, bed.points.stored(ARGENTE_ID).get("70061~002"));
		assertEquals(T3, bed.engine.checkpoint(ARGENTE_ID), "advanced");
		assertEquals(T2, bed.engine.checkpoint(FRANCHISE_ID));
		assertEquals(0, bed.points.wholeLoads);
	}

	@Test
	@DisplayName("A run answering no row: a success, nothing changed or deactivated, every checkpoint kept")
	void noRowAnswered() {
		bed.job().syncCatalogue();
		bed.items.clear(); // the ERP answers no row at either point

		bed.job().syncCatalogue();

		assertEquals(ErpCommunicationStatus.SUCCESS, bed.logged.get(1)[0]);
		assertNull(bed.logged.get(1)[1], "no warning");
		Map<String, Object> items = bed.part("items");
		assertEquals(0, items.get("read"));
		assertEquals(0, items.get("changed"));
		assertEquals(0, items.get("deactivated"));
		assertEquals(T2, bed.engine.checkpoint(ARGENTE_ID));
		assertEquals(T2, bed.engine.checkpoint(FRANCHISE_ID));
		assertTrue(bed.points.stored(ARGENTE_ID).values().stream().allMatch(row -> row.active), "not deactivated by absence");
		assertTrue(bed.points.stored(FRANCHISE_ID).values().stream().allMatch(row -> row.active));
		assertTrue(item("70061~001").getActive());
		assertTrue(item("70061~002").getActive());
	}

	@Test
	@DisplayName("Rows answered inactive: the item stays active while a stored row is; inactive with its last; active again")
	void inactiveRowsThenTheItem() {
		bed.engine.items();

		franchise002.setActive(Boolean.FALSE);
		franchise002.setLastModifiedAt(T3);
		CatalogueRun<ErpItemDTO> franchiseOff = bed.engine.items();
		assertFalse(bed.points.stored(FRANCHISE_ID).get("70061~002").active);
		assertEquals(1, pointSummary(franchiseOff, FRANCHISE).get("deactivated"));
		assertTrue(item("70061~002").getActive(), "its row at ARGENTE (stored, not answered) is active");
		assertEquals(0, franchiseOff.count("deactivated"));

		argente002.setActive(Boolean.FALSE);
		argente002.setLastModifiedAt(T4);
		CatalogueRun<ErpItemDTO> lastOff = bed.engine.items();
		assertFalse(bed.points.stored(ARGENTE_ID).get("70061~002").active);
		assertFalse(item("70061~002").getActive(), "its last active row answered inactive");
		assertEquals(1, lastOff.count("deactivated"));
		assertTrue(item("70061~001").getActive());

		argente002.setActive(Boolean.TRUE);
		argente002.setLastModifiedAt(T5);
		CatalogueRun<ErpItemDTO> backOn = bed.engine.items();
		assertTrue(bed.points.stored(ARGENTE_ID).get("70061~002").active);
		assertTrue(item("70061~002").getActive(), "an active row answered makes it active again");
		assertEquals(1, backOn.count("changed"));
		assertFalse(bed.points.stored(FRANCHISE_ID).get("70061~002").active, "FRANCHISE's row as it was");
	}

	@Test
	@DisplayName("A point whose rows fail keeps its checkpoint; the other point's checkpoint is written; the next run catches up")
	void failedPointKeepsItsCheckpoint() {
		bed.engine.items();
		argente001.setUnitPrice(new BigDecimal("60"));
		argente001.setLastModifiedAt(T3);
		franchise001.setUnitPrice(new BigDecimal("60"));
		franchise001.setLastModifiedAt(T3);
		bed.points.failing.add(FRANCHISE_ID);

		IllegalStateException failed = assertThrows(IllegalStateException.class, () -> bed.engine.items());

		assertTrue(failed.getMessage().startsWith("Stock point FRANCHISE: packet 1 failed"), failed.getMessage());
		assertEquals(T3, bed.engine.checkpoint(ARGENTE_ID));
		assertEquals(T2, bed.engine.checkpoint(FRANCHISE_ID), "kept");
		assertEquals(60.0, bed.points.stored(ARGENTE_ID).get("70061~001").unitPrice);
		assertEquals(50.0, bed.points.stored(FRANCHISE_ID).get("70061~001").unitPrice);

		bed.points.failing.clear();
		bed.engine.items();
		assertEquals("FRANCHISE since " + T2, bed.sinceAsked.get(bed.sinceAsked.size() - 1), "read again from it");
		assertEquals(60.0, bed.points.stored(FRANCHISE_ID).get("70061~001").unitPrice);
		assertEquals(T3, bed.engine.checkpoint(FRANCHISE_ID));
	}

	@Test
	@DisplayName("Same scenario in both modes: a row gone from the ERP is deactivated by a whole read, kept by a read of changes")
	void bothModesSideBySide() {
		CatalogueTestBed whole = changesOnlyBed(false);
		CatalogueTestBed changes = changesOnlyBed(true);
		whole.engine.items();
		changes.engine.items();
		whole.items.get(FRANCHISE).remove(1); // 70061~002 no longer answered at FRANCHISE
		changes.items.get(FRANCHISE).remove(1);

		CatalogueRun<ErpItemDTO> wholeRun = whole.engine.items();
		CatalogueRun<ErpItemDTO> changesRun = changes.engine.items();

		assertFalse(whole.points.stored(FRANCHISE_ID).get("70061~002").active, "whole read: gone = inactive");
		assertEquals(1, pointSummary(wholeRun, FRANCHISE).get("deactivated"));
		assertTrue(changes.points.stored(FRANCHISE_ID).get("70061~002").active, "changes only: not answered = unchanged");
		assertEquals(0, pointSummary(changesRun, FRANCHISE).get("deactivated"));
		assertTrue(whole.tables.itemByCode("70061~002").get().getActive(), "still at ARGENTE");
		assertTrue(changes.tables.itemByCode("70061~002").get().getActive());
		// The whole read never asks a time and loads every row of a point; the read of changes the opposite
		assertTrue(whole.sinceAsked.isEmpty());
		assertTrue(whole.points.wholeLoads > 0);
		assertNull(wholeRun.getSummary().get("changesOnly"));
		assertEquals(4, changes.sinceAsked.size());
		assertEquals(0, changes.points.wholeLoads);
	}

	// ─── Helpers ────────────────────────────────────────────────

	/** A variant row as the ZEN sample has it, active, at this ERP time. */
	private static ErpItemDTO row(String code, OffsetDateTime modified) {
		ErpItemDTO row = new ErpItemDTO();
		row.setExternalId(code);
		row.setCode(code);
		row.setName("CHEMISE");
		row.setUnitPrice(new BigDecimal("50"));
		row.setDefaultVAT(19);
		row.setActive(Boolean.TRUE);
		row.setNavItemNo("70061");
		row.setVariantCode(code.substring(code.indexOf('~') + 1));
		row.setColorDescription("Noir");
		row.setLastModifiedAt(modified);
		return row;
	}

	private Item item(String code) {
		return bed.tables.itemByCode(code).orElseThrow(() -> new AssertionError("no item " + code));
	}

	private static java.util.List<String> codes(CatalogueRun<ErpItemDTO> run) {
		java.util.List<String> codes = new ArrayList<>();
		run.getHanded().forEach(item -> codes.add(item.getCode()));
		return codes;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> pointSummary(CatalogueRun<ErpItemDTO> run, String pointCode) {
		return (Map<String, Object>) ((Map<String, Object>) run.getSummary().get("stockPoints")).get(pointCode);
	}
}
