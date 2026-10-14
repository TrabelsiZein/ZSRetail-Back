package com.digithink.zsretail.core.erp.engine;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.digithink.zsretail.core.erp.service.ErpItemBootstrapService;
import com.digithink.zsretail.core.erp.service.ItemVariant;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.erp.api.CatalogueSettings;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCatalogueSource;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;

/**
 * Item variants (ZEN): the catalogue engine and the job SYNC_CATALOGUE with the real import (ErpItemBootstrapService
 * over the in-memory tables of InMemoryItems), a fake ERP and fake points de stock, the head office read as
 * JpaHeadOfficeCatalogue projects it (its queries run on H2 in JpaHeadOfficeCatalogueTest). No Spring, no live call.
 */
final class CatalogueTestBed {

	static final String ARGENTE = "ARGENTE";
	static final String FRANCHISE = "FRANCHISE";

	final InMemoryItems tables = new InMemoryItems(1);
	/** The ERP's item rows by location code, as templates: each read hands copies (the engine fills blank names). */
	final Map<String, List<ErpItemDTO>> items = new LinkedHashMap<>();
	/** The ERP's categories page. */
	final List<ErpItemCategoryDTO> categories = new ArrayList<>();
	final List<ErpItemFamilyDTO> families = new ArrayList<>();
	final List<ErpItemSubFamilyDTO> subFamilies = new ArrayList<>();
	final List<ErpItemSectionDTO> sections = new ArrayList<>();
	/** True: the page read through the 2-argument ErpCategories (an ERP without catégories and sections). */
	boolean twoLevelsOnly;
	/**
	 * Step 6: true, the ERP reads changes only: items(location, since) answers the rows whose lastModifiedAt is at or
	 * after since (every row when since is null), as the connector filters them.
	 */
	boolean changesOnly;
	/** Step 6: each read of changes, "LOCATION since VALUE" (VALUE null for a whole read). */
	final List<String> sinceAsked = new ArrayList<>();
	/** How many times the import of catégories and of sections was called. */
	int categoryImports;
	int sectionImports;

	final Points points = new Points();
	final State state = new State();
	/** The communications log of the job: status, message, summary. */
	final List<Object[]> logged = new ArrayList<>();
	final ErpItemBootstrapService bootstrap;
	final CatalogueImport importer;
	final CatalogueEngine engine;

	CatalogueTestBed() {
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return null; // no TAX_STAMP_ERP_ITEM_CODE
			}
		};
		bootstrap = new ErpItemBootstrapService(tables.familyRepository(), tables.subFamilyRepository(),
				tables.itemRepository(), tables.barcodeRepository(), null, null, null, null, null, setup,
				tables.categoryRepository(), tables.sectionRepository());
		points.points.add(new StockPoints.Point(1, ARGENTE, "Argente"));
		points.points.add(new StockPoints.Point(2, FRANCHISE, "Franchise"));
		CatalogueSettings settings = new CatalogueSettings() {
			@Override
			public int packetSize() {
				return 500;
			}

			@Override
			public boolean dryRun() {
				return false;
			}

			@Override
			public Integer defaultVat() {
				return 19;
			}
		};
		importer = new CatalogueImport() {
			@Override
			public void items(List<ErpItemDTO> packet) {
				bootstrap.importItems(packet);
			}

			@Override
			public void barcodes(List<ErpItemBarcodeDTO> packet) {
				bootstrap.importItemBarcodes(packet);
			}

			@Override
			public void families(List<ErpItemFamilyDTO> packet) {
				bootstrap.importItemFamilies(packet);
			}

			@Override
			public void subFamilies(List<ErpItemSubFamilyDTO> packet) {
				bootstrap.importItemSubFamilies(packet);
			}

			@Override
			public void categories(List<ErpItemCategoryDTO> packet) {
				categoryImports++;
				bootstrap.importItemCategories(packet);
			}

			@Override
			public void sections(List<ErpItemSectionDTO> packet) {
				sectionImports++;
				bootstrap.importItemSections(packet);
			}
		};
		engine = new CatalogueEngine(new Source(), new Tables(), points, state, settings, importer);
	}

	/** The job SYNC_CATALOGUE on this bed, its log in {@link #logged}. */
	CatalogueJob job() {
		return new CatalogueJob(engine, importer, points,
				(status, summary, message, start, end) -> logged.add(new Object[] { status, message, summary }));
	}

	/** One part of the last run's summary. */
	@SuppressWarnings("unchecked")
	Map<String, Object> part(String name) {
		Map<String, Object> summary = (Map<String, Object>) logged.get(logged.size() - 1)[2];
		return (Map<String, Object>) ((Map<String, Object>) summary.get("parts")).get(name);
	}

	// ─── Rows of the ERP ────────────────────────────────────────

	static ErpItemCategoryDTO category(String code, String name, int order) {
		ErpItemCategoryDTO category = new ErpItemCategoryDTO();
		category.setExternalId(code);
		category.setCode(code);
		category.setName(name);
		category.setDisplayOrder(order);
		category.setActive(Boolean.TRUE);
		return category;
	}

	static ErpItemFamilyDTO family(String code, String name, String categoryCode) {
		ErpItemFamilyDTO family = new ErpItemFamilyDTO();
		family.setExternalId(code);
		family.setCode(code);
		family.setName(name);
		family.setActive(Boolean.TRUE);
		family.setCategoryExternalId(categoryCode);
		return family;
	}

	static ErpItemSubFamilyDTO subFamily(String code, String name, String familyCode) {
		ErpItemSubFamilyDTO subFamily = new ErpItemSubFamilyDTO();
		subFamily.setExternalId(code);
		subFamily.setCode(code);
		subFamily.setName(name);
		subFamily.setActive(Boolean.TRUE);
		subFamily.setFamilyExternalId(familyCode);
		return subFamily;
	}

	static ErpItemSectionDTO section(String code, String name, String subFamilyCode, int order) {
		ErpItemSectionDTO section = new ErpItemSectionDTO();
		section.setExternalId(code);
		section.setCode(code);
		section.setName(name);
		section.setDisplayOrder(order);
		section.setActive(Boolean.TRUE);
		section.setSubFamilyExternalId(subFamilyCode);
		return section;
	}

	/** A copy of every field the engine and the import read. */
	static ErpItemDTO copy(ErpItemDTO from) {
		ErpItemDTO to = new ErpItemDTO();
		to.setExternalId(from.getExternalId());
		to.setCode(from.getCode());
		to.setName(from.getName());
		to.setDescription(from.getDescription());
		to.setFamilyExternalId(from.getFamilyExternalId());
		to.setSubFamilyExternalId(from.getSubFamilyExternalId());
		to.setUnitPrice(from.getUnitPrice());
		to.setDefaultVAT(from.getDefaultVAT());
		to.setItemDiscGroup(from.getItemDiscGroup());
		to.setMaximumAuthorizedDiscount(from.getMaximumAuthorizedDiscount());
		to.setActive(from.getActive());
		to.setNavItemNo(from.getNavItemNo());
		to.setVariantCode(from.getVariantCode());
		to.setVariantDescription(from.getVariantDescription());
		to.setSizeCode(from.getSizeCode());
		to.setSizeDescription(from.getSizeDescription());
		to.setSizeSortOrder(from.getSizeSortOrder());
		to.setColorCode(from.getColorCode());
		to.setColorDescription(from.getColorDescription());
		to.setChoiceCode(from.getChoiceCode());
		to.setChoiceDescription(from.getChoiceDescription());
		to.setSeasonCode(from.getSeasonCode());
		to.setDivisionCode(from.getDivisionCode());
		to.setCategoryCode(from.getCategoryCode());
		to.setSectionCode(from.getSectionCode());
		to.setBrand(from.getBrand());
		to.setClearance(from.getClearance());
		to.setClearancePrice(from.getClearancePrice());
		to.setLastModifiedAt(from.getLastModifiedAt());
		return to;
	}

	static <T> ErpRead<T> read(List<T> rows) {
		return new ErpRead<>(new ArrayList<>(rows), rows.size(), Collections.emptyMap(), Collections.emptyMap(), null);
	}

	// ─── Fakes ──────────────────────────────────────────────────

	/** The ERP: the categories page, the item rows of each location, no barcode (none needed here). */
	private final class Source implements ErpCatalogueSource {

		@Override
		public ErpCategories categories() {
			return twoLevelsOnly ? new ErpCategories(read(families), read(subFamilies))
					: new ErpCategories(read(categories), read(families), read(subFamilies), read(sections));
		}

		@Override
		public ErpRead<ErpItemDTO> items(String locationCode) {
			return read(items.getOrDefault(locationCode, new ArrayList<>()).stream().map(CatalogueTestBed::copy)
					.collect(Collectors.toList()));
		}

		@Override
		public ErpRead<ErpItemDTO> items(String locationCode, OffsetDateTime since) {
			sinceAsked.add(locationCode + " since " + since);
			return read(items.getOrDefault(locationCode, new ArrayList<>()).stream()
					.filter(row -> since == null || row.getLastModifiedAt() == null || !row.getLastModifiedAt().isBefore(since))
					.map(CatalogueTestBed::copy).collect(Collectors.toList()));
		}

		@Override
		public boolean readsChangesOnly() {
			return changesOnly;
		}

		@Override
		public ErpRead<ErpBarcodeRow> barcodesAfter(long position) {
			return new ErpRead<>(new ArrayList<>(), 0, Collections.emptyMap(), new ArrayList<>());
		}

		@Override
		public ErpRead<ErpBarcodeRow> barcodesOfItems(List<String> itemCodes) {
			return new ErpRead<>(new ArrayList<>(), 0, Collections.emptyMap(), new ArrayList<>());
		}

		@Override
		public String pageLabel(Part part) {
			return part.name();
		}

		@Override
		public String pageUrl(Part part) {
			return "test:" + part.name();
		}
	}

	/** The head office tables of InMemoryItems, read as JpaHeadOfficeCatalogue projects them. */
	private final class Tables implements HeadOfficeCatalogue {

		@Override
		public Map<String, Family> families() {
			Map<String, Family> found = new LinkedHashMap<>();
			for (ItemFamily f : tables.families.values()) {
				found.put(f.getCode(), new Family(f.getCode(), f.getName(), f.getDescription(), f.getActive(),
						f.getErpExternalId(), f.getCategoryCode()));
			}
			return found;
		}

		@Override
		public Map<String, SubFamily> subFamilies() {
			Map<String, SubFamily> found = new LinkedHashMap<>();
			for (ItemSubFamily s : tables.subFamilies.values()) {
				found.put(s.getCode(), new SubFamily(s.getCode(), s.getName(), s.getDescription(), s.getActive(),
						s.getErpExternalId(), s.getItemFamily() == null ? null : s.getItemFamily().getCode()));
			}
			return found;
		}

		@Override
		public Map<String, Level> categories() {
			Map<String, Level> found = new LinkedHashMap<>();
			for (ItemCategory c : tables.categories.values()) {
				found.put(c.getCode(), new Level(c.getCode(), c.getName(), c.getDescription(), c.getDisplayOrder(),
						c.getActive(), c.getErpExternalId(), null));
			}
			return found;
		}

		@Override
		public Map<String, Level> sections() {
			Map<String, Level> found = new LinkedHashMap<>();
			for (ItemSection s : tables.sections.values()) {
				found.put(s.getCode(), new Level(s.getCode(), s.getName(), s.getDescription(), s.getDisplayOrder(),
						s.getActive(), s.getErpExternalId(), s.getSubFamilyCode()));
			}
			return found;
		}

		@Override
		public Map<String, HeadOfficeCatalogue.Item> items() {
			Map<String, HeadOfficeCatalogue.Item> found = new LinkedHashMap<>();
			// The entity (inside this class, Item is HeadOfficeCatalogue.Item)
			for (com.digithink.zsretail.core.model.Item i : tables.items.values()) {
				ItemVariant variant = new ItemVariant(i.getNavItemNo(), i.getVariantCode(), i.getVariantDescription(),
						i.getSizeCode(), i.getSizeDescription(), i.getSizeSortOrder(), i.getColorCode(),
						i.getColorDescription(), i.getChoiceCode(), i.getChoiceDescription(), i.getSeasonCode(),
						i.getDivisionCode(), i.getCategoryCode(), i.getSectionCode());
				found.put(i.getItemCode(), new HeadOfficeCatalogue.Item(i.getItemCode(), i.getName(), i.getDescription(),
						i.getUnitPrice(), i.getDefaultVAT(), i.getActive(), i.getErpExternalId(), i.getItemDiscGroup(),
						i.getMaximumAuthorizedDiscount(), i.getItemFamily() == null ? null : i.getItemFamily().getCode(),
						i.getItemSubFamily() == null ? null : i.getItemSubFamily().getCode(), variant, i.getBrand()));
			}
			return found;
		}

		@Override
		public Map<String, Barcode> barcodes(Collection<String> values) {
			Map<String, Barcode> found = new LinkedHashMap<>();
			for (ItemBarcode b : tables.barcodes.values()) {
				if (values.contains(b.getBarcode())) {
					found.put(b.getBarcode(), new Barcode(b.getBarcode(), b.getItem().getItemCode(), b.getActive()));
				}
			}
			return found;
		}

		@Override
		public String taxStampErpCode() {
			return null;
		}
	}

	/** ho_stock_point and ho_stock_point_item: a row whose item is not at the head office is left out. */
	final class Points implements StockPoints {
		final List<Point> points = new ArrayList<>();
		final Map<Long, Map<String, Row>> rows = new HashMap<>();
		int writes;
		/** Step 6: loads of every row of a point, and loads of the rows of some codes ("point:code,code"). */
		int wholeLoads;
		final List<String> codeLoads = new ArrayList<>();
		/** Step 6: the points whose writes fail. */
		final Set<Long> failing = new HashSet<>();

		@Override
		public List<Point> activePoints() {
			return new ArrayList<>(points);
		}

		@Override
		public Map<String, Row> rows(long pointId) {
			wholeLoads++;
			return new LinkedHashMap<>(rows.getOrDefault(pointId, new TreeMap<>()));
		}

		/** The rows as stored, without counting a load (the tests' view). */
		Map<String, Row> stored(long pointId) {
			return new LinkedHashMap<>(rows.getOrDefault(pointId, new TreeMap<>()));
		}

		@Override
		public Map<String, Row> rows(long pointId, Collection<String> itemCodes) {
			codeLoads.add(pointId + ":" + String.join(",", itemCodes));
			Map<String, Row> ofPoint = rows.getOrDefault(pointId, new TreeMap<>());
			Map<String, Row> found = new LinkedHashMap<>();
			itemCodes.stream().filter(ofPoint::containsKey).forEach(code -> found.put(code, ofPoint.get(code)));
			return found;
		}

		@Override
		public int write(long pointId, List<Row> packet) {
			if (failing.contains(pointId)) {
				throw new IllegalStateException("connection lost");
			}
			writes++;
			Map<String, Row> ofPoint = rows.computeIfAbsent(pointId, id -> new TreeMap<>());
			int written = 0;
			for (Row row : packet) {
				if (tables.itemByCode(row.itemCode).isPresent()) {
					ofPoint.put(row.itemCode, row);
					written++;
				}
			}
			return written;
		}

		@Override
		public void recordRead(long pointId, String status, String summary) {
		}
	}

	/** The state table. */
	static final class State implements CatalogueState {
		final Map<String, String> values = new TreeMap<>();

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
