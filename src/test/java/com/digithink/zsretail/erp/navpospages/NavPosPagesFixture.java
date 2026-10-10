package com.digithink.zsretail.erp.navpospages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.erp.dto.PullOperationResult;
import com.digithink.zsretail.erp.engine.CatalogueEngine;
import com.digithink.zsretail.erp.engine.CatalogueImport;
import com.digithink.zsretail.erp.engine.CatalogueRun;
import com.digithink.zsretail.erp.engine.CatalogueState;
import com.digithink.zsretail.erp.engine.HeadOfficeCatalogue;
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesCatalogueSource;
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesSource;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesCatalogueSettings;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.connector.NavPosPagesConnector;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCollection;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;
import com.digithink.zsretail.erp.navpospages.mapper.NavPosPagesMapper;
import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesInvoices;
import com.digithink.zsretail.erp.service.ErpSyncWarningException;
import com.digithink.zsretail.erp.spi.StockPoints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.TextNode;


/**
 * ERP catalogue, step 6: the samples of src/test/resources/navpospages, the fakes and the wiring shared by the tests of
 * the catalogue engine (erp/engine/CatalogueEngineTest) and of the connector's own runs (NavPosPagesInvoicesTest). The
 * head office tables are a fake that applies what the import (ErpItemBootstrapService) applies; the ERP a fake source of
 * page rows, read by the engine through the connector's adapter (NavPosPagesCatalogueSource) with the connector's
 * settings; the state table a map. No live call. Code cleanup step 3: was the first and last part of
 * NavPosPagesSyncTest.
 */
public abstract class NavPosPagesFixture {

	protected FakeSource erp;
	protected FakeHeadOffice headOffice;
	protected FakeState state;
	protected FakeStockPoints stockPoints;
	protected NavPosPagesProperties properties;
	protected CatalogueEngine engine;
	/** Invoices from the ERP: the connector's thin part, on the same fake pages. */
	protected NavPosPagesInvoices invoiceReads;
	/** The size of each packet of items applied, in order. */
	protected final List<Integer> packets = new ArrayList<>();
	/** The n-th packet of items from now fails, its transaction rolled back (0: none fails). */
	protected int failAtPacket;
	/** Runs inside each packet of items, before it is applied. */
	protected Runnable duringPacket = () -> {
	};
	/** The same three for the packets of barcodes. */
	protected final List<Integer> barcodePackets = new ArrayList<>();
	protected int failAtBarcodePacket;
	protected Runnable duringBarcodePacket = () -> {
	};
	/** Barcodes the import leaves out without failing (as the real one does with a warning). */
	protected final Set<String> notSaving = new HashSet<>();

	@BeforeEach
	public void setUp() {
		erp = new FakeSource();
		erp.categories = NavPosPagesTestSupport.rows("ItemCategory", new TypeReference<NavPosCollection<NavPosCategoryRow>>() {
		});
		erp.items = NavPosPagesTestSupport.rows("PointStockPOS", new TypeReference<NavPosCollection<NavPosStockRow>>() {
		});
		erp.barcodes = NavPosPagesTestSupport.rows("ItemBarCodePOS", new TypeReference<NavPosCollection<NavPosBarcodeRow>>() {
		});
		headOffice = new FakeHeadOffice();
		state = new FakeState();
		stockPoints = new FakeStockPoints();
		stockPoints.points.add(new StockPoints.Point(1, "FRANCHISE", "Franchise")); // release 2.2: one point
		properties = NavPosPagesTestSupport.properties();
		NavPosPagesMapper mapper = new NavPosPagesMapper(properties.getDefaultVat(), properties.getPriceIncludesVat());
		// The engine reads the fake pages through the connector's adapter, with the connector's settings
		engine = new CatalogueEngine(new NavPosPagesCatalogueSource(erp, mapper, properties), headOffice, stockPoints, state,
				new NavPosPagesCatalogueSettings(properties), new CatalogueImport() {
			@Override
			public void items(List<ErpItemDTO> packet) {
				duringPacket.run();
				if (failAtPacket > 0 && --failAtPacket == 0) {
					throw new IllegalStateException("connection lost");
				}
				headOffice.importItems(packet);
				packets.add(packet.size());
			}

			@Override
			public void barcodes(List<ErpItemBarcodeDTO> packet) {
				duringBarcodePacket.run();
				if (failAtBarcodePacket > 0 && --failAtBarcodePacket == 0) {
					throw new IllegalStateException("connection lost");
				}
				headOffice.importBarcodes(packet.stream().filter(dto -> !notSaving.contains(dto.getBarcode()))
						.collect(Collectors.toList()));
				barcodePackets.add(packet.size());
			}
		});
		invoiceReads = new NavPosPagesInvoices(erp, headOffice, properties, mapper);
	}

	// ─── Running the four jobs, the import applying what it is handed ────

	protected CatalogueRun<ErpItemFamilyDTO> families() {
		CatalogueRun<ErpItemFamilyDTO> run = engine.families();
		headOffice.importFamilies(run.getHanded());
		return run;
	}

	protected CatalogueRun<ErpItemSubFamilyDTO> subFamilies() {
		CatalogueRun<ErpItemSubFamilyDTO> run = engine.subFamilies();
		headOffice.importSubFamilies(run.getHanded());
		return run;
	}

	/** The items run applies its packets itself. */
	protected CatalogueRun<ErpItemDTO> items() {
		return engine.items();
	}

	/** The barcode run applies its packets itself, until it has caught up. */
	protected CatalogueRun<ErpItemBarcodeDTO> barcodes() {
		return engine.barcodes();
	}

	protected static List<String> barcodeValues(CatalogueRun<ErpItemBarcodeDTO> run) {
		return run.getHanded().stream().map(ErpItemBarcodeDTO::getBarcode).collect(Collectors.toList());
	}

	/** Families, sub-families and every item at the head office (no cap reached). */
	protected void loadCatalogue() {
		families();
		subFamilies();
		items();
		assertEquals(0, items().getHanded().size());
	}

	/** The whole catalogue and every barcode. */
	protected void loadAll() {
		loadCatalogue();
		assertEquals(Boolean.TRUE, barcodes().getSummary().get("caughtUp"));
	}

	protected static List<String> codes(List<ErpItemDTO> items) {
		return items.stream().map(ErpItemDTO::getCode).collect(Collectors.toList());
	}

	protected NavPosStockRow erpItem(String code) {
		return erp.items.stream().filter(row -> row.getItemNo().equals(code)).findFirst().get();
	}

	// ─── Fakes ──────────────────────────────────────────────────

	protected static HeadOfficeCatalogue.Item item(String code, String erpId, boolean active) {
		return new HeadOfficeCatalogue.Item(code, code, "", 1.0, 19, active, erpId, null, null, null, null);
	}

	/** The ERP: the three pages in memory; barcodes by Entry_No in pages of barcode-page-size. */
	public final class FakeSource implements NavPosPagesSource {
		public List<NavPosCategoryRow> categories = new ArrayList<>();
		public List<NavPosStockRow> items = new ArrayList<>();
		public List<NavPosBarcodeRow> barcodes = new ArrayList<>();
		public List<String> askedItems = new ArrayList<>();

		@Override
		public List<NavPosCategoryRow> readCategories() {
			return new ArrayList<>(categories);
		}

		/** Stock points: the rows of the other points by code (any other code answers items), the codes read, those that fail. */
		public Map<String, List<NavPosStockRow>> otherPoints = new HashMap<>();
		public List<String> pointReads = new ArrayList<>();
		public Set<String> failingPoints = new HashSet<>();

		@Override
		public List<NavPosStockRow> readItems(String locationCode) {
			pointReads.add(locationCode);
			if (failingPoints.contains(locationCode)) {
				throw new IllegalStateException("reading the page PointStockPOS failed: timeout");
			}
			return new ArrayList<>(otherPoints.getOrDefault(locationCode, items));
		}

		/** Code cleanup step 3 proof: when set, the barcode pages by Entry_No (a null Entry_No allowed); else the list. */
		public java.util.function.Function<Long, List<NavPosBarcodeRow>> barcodePages;

		@Override
		public List<NavPosBarcodeRow> readBarcodesAfter(long entryNo) {
			if (barcodePages != null) {
				return new ArrayList<>(barcodePages.apply(entryNo));
			}
			return barcodes.stream().filter(row -> row.getEntryNo() > entryNo)
					.sorted(Comparator.comparing(NavPosBarcodeRow::getEntryNo)).limit(properties.getBarcodePageSize())
					.collect(Collectors.toList());
		}

		@Override
		public List<NavPosBarcodeRow> readBarcodesOfItems(List<String> itemNos) {
			askedItems.addAll(itemNos);
			return barcodes.stream().filter(row -> itemNos.contains(row.getItemNo())).collect(Collectors.toList());
		}

		/** Invoices from the ERP: the invoices page, by number; each read written "FVV26 after FVV26000000001". */
		public List<NavPosInvoiceRow> invoices = new ArrayList<>();
		public List<String> invoiceReads = new ArrayList<>();

		/** Release 2.2: the lowest number of the page with this prefix; written "first FVV" in invoiceReads. */
		@Override
		public String readFirstInvoiceNumber(String numberPrefix) {
			invoiceReads.add("first " + numberPrefix);
			return invoices.stream().map(row -> row.text("No")).filter(no -> no.startsWith(numberPrefix)).sorted()
					.findFirst().orElse(null);
		}

		@Override
		public List<NavPosInvoiceRow> readInvoicesAfter(String yearPrefix, String afterNumber) {
			invoiceReads.add(yearPrefix + " after " + afterNumber);
			return invoices.stream().filter(row -> row.text("No").startsWith(yearPrefix))
					.filter(row -> afterNumber == null || row.text("No").compareTo(afterNumber) > 0)
					.sorted(Comparator.comparing(row -> row.text("No")))
					.limit(properties.getInvoices().getMaxPerRun()).collect(Collectors.toList());
		}

		@Override
		public List<NavPosInvoiceRow> readInvoicesByNumbers(List<String> numbers) {
			invoiceReads.add("numbers " + numbers);
			return invoices.stream().filter(row -> numbers.contains(row.text("No")))
					.sorted(Comparator.comparing(row -> row.text("No"))).collect(Collectors.toList());
		}

		@Override
		public String pageUrl(String page) {
			return NavPosPagesTestSupport.COMPANY_URL + page;
		}
	}

	/** The head office tables, and the import as ErpItemBootstrapService applies it. */
	public static final class FakeHeadOffice implements HeadOfficeCatalogue {
		public final Map<String, Family> families = new TreeMap<>();
		public final Map<String, SubFamily> subFamilies = new TreeMap<>();
		public final Map<String, Item> items = new TreeMap<>();
		public final Map<String, Barcode> barcodes = new TreeMap<>();
		public String taxStamp;
		/** Release 2.2: General Setup "Read ERP invoices after number"; null when empty. */
		public String invoicesReadAfter;

		@Override
		public String invoicesReadAfter() {
			return invoicesReadAfter;
		}

		@Override
		public Map<String, Family> families() {
			return new LinkedHashMap<>(families);
		}

		@Override
		public Map<String, SubFamily> subFamilies() {
			return new LinkedHashMap<>(subFamilies);
		}

		@Override
		public Map<String, Item> items() {
			return new LinkedHashMap<>(items);
		}

		@Override
		public Map<String, Barcode> barcodes(Collection<String> values) {
			Map<String, Barcode> found = new LinkedHashMap<>();
			values.stream().filter(barcodes::containsKey).forEach(value -> found.put(value, barcodes.get(value)));
			return found;
		}

		@Override
		public String taxStampErpCode() {
			return taxStamp;
		}

		public void importFamilies(List<ErpItemFamilyDTO> handed) {
			for (ErpItemFamilyDTO dto : handed) {
				families.put(dto.getCode(), new Family(dto.getCode(), name(dto.getName(), dto.getCode()),
						text(dto.getDescription()), dto.getActive(), dto.getExternalId()));
			}
		}

		public void importSubFamilies(List<ErpItemSubFamilyDTO> handed) {
			for (ErpItemSubFamilyDTO dto : handed) {
				if (dto.getFamilyExternalId() != null && families.containsKey(dto.getFamilyExternalId())) { // otherwise skipped
					subFamilies.put(dto.getCode(), new SubFamily(dto.getCode(), name(dto.getName(), dto.getCode()),
							text(dto.getDescription()), dto.getActive(), dto.getExternalId(), dto.getFamilyExternalId()));
				}
			}
		}

		public void importItems(List<ErpItemDTO> handed) {
			for (ErpItemDTO dto : handed) {
				if (taxStamp != null && dto.getCode().equals(taxStamp)) {
					continue;
				}
				Item before = items.get(dto.getCode());
				String family = dto.getFamilyExternalId() != null && families.containsKey(dto.getFamilyExternalId())
						? dto.getFamilyExternalId()
						: before == null ? null : before.familyCode;
				String subFamily = dto.getSubFamilyExternalId() != null && subFamilies.containsKey(dto.getSubFamilyExternalId())
						? dto.getSubFamilyExternalId()
						: before == null ? null : before.subFamilyCode;
				items.put(dto.getCode(), new Item(dto.getCode(), name(dto.getName(), dto.getCode()),
						text(dto.getDescription()), dto.getUnitPrice() == null ? null : dto.getUnitPrice().doubleValue(),
						dto.getDefaultVAT(), dto.getActive(), dto.getExternalId(), text(dto.getItemDiscGroup()),
						dto.getMaximumAuthorizedDiscount(), family, subFamily));
			}
		}

		public void importBarcodes(List<ErpItemBarcodeDTO> handed) {
			for (ErpItemBarcodeDTO dto : handed) {
				if (items.containsKey(dto.getItemExternalId())) {
					barcodes.put(dto.getBarcode(), new Barcode(dto.getBarcode(), dto.getItemExternalId(), true));
				}
			}
		}

		private static String name(String name, String code) {
			return name == null || name.trim().isEmpty() ? code : name;
		}

		private static String text(String value) {
			return value == null ? "" : value;
		}
	}

	/** Stock points: ho_stock_point (the active points, in list order) and ho_stock_point_item, by point id. */
	public final class FakeStockPoints implements StockPoints {
		public final List<Point> points = new ArrayList<>();
		public final Map<Long, Map<String, Row>> rows = new HashMap<>();
		public int writes;

		@Override
		public List<Point> activePoints() {
			return new ArrayList<>(points);
		}

		@Override
		public Map<String, Row> rows(long pointId) {
			return new LinkedHashMap<>(rows.getOrDefault(pointId, new TreeMap<>()));
		}

		/** As HoStockPointRows: a row whose item is not at the head office is left out. */
		@Override
		public int write(long pointId, List<Row> packet) {
			writes++;
			Map<String, Row> ofPoint = rows.computeIfAbsent(pointId, id -> new TreeMap<>());
			int written = 0;
			for (Row row : packet) {
				if (headOffice.items.containsKey(row.itemCode)) {
					ofPoint.put(row.itemCode, row);
					written++;
				}
			}
			return written;
		}

		/** Step 4: the last read of each point, "STATUS summary". */
		public final Map<Long, String> lastReads = new HashMap<>();

		@Override
		public void recordRead(long pointId, String status, String summary) {
			lastReads.put(pointId, status + " " + summary);
		}

		public Row row(long pointId, String itemCode) {
			return rows.getOrDefault(pointId, new TreeMap<>()).get(itemCode);
		}
	}

	/** The state table. */
	public static final class FakeState implements CatalogueState {
		public final Map<String, String> values = new TreeMap<>();

		@Override
		public String get(String key) {
			return values.get(key);
		}

		@Override
		public void put(String key, String value) {
			values.put(key, value);
		}

		@Override
		public void remove(String key) {
			values.remove(key);
		}

		@Override
		public List<String> keysStartingWith(String prefix) {
			return values.keySet().stream().filter(key -> key.startsWith(prefix)).collect(Collectors.toList());
		}
	}
}
