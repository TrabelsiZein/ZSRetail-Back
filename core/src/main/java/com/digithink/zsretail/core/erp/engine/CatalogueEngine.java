package com.digithink.zsretail.core.erp.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnErpAbility;
import com.digithink.zsretail.core.erp.service.ItemVariant;
import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.CatalogueSettings;
import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCatalogueSource;
import com.digithink.zsretail.erp.api.ErpCatalogueSource.Part;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.erp.api.RunSummary;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;

/**
 * ERP catalogue, step 6: changes only. Each run reads a whole page of the ERP (GET only, through the connector's
 * {@link ErpCatalogueSource}), compares it with the head office tables and takes only the new and changed rows, in a
 * stable order (by code; barcodes by position). The items and barcode runs apply them all themselves, in packets of
 * packet-size, each packet in its own transaction ({@link CatalogueImport}), one run of each at a time; families and
 * sub-families hand one packet to the import (ErpItemBootstrapService, unchanged) per run. The head office tables are the
 * memory of what was seen: a row the import did not save is still different at the next run and is taken again.
 * <ul>
 * <li><b>Order.</b> Sub-families wait for the families, items for both: the waiting run reads the categories page (small)
 * and waits while a family or sub-family of the ERP is not at the head office yet. Barcodes wait for the items: each item
 * run writes, before handing over, how many items of the ERP are not at the head office yet; the barcode run waits while
 * that number is above 0 or no item run happened. The items run counts before applying, then again once every packet
 * is applied: 0 proves the new items were saved.</li>
 * <li><b>Catégories and sections</b> (item variants, ZEN, step 4): above the families and below the sub-families, read
 * from the same categories page, handed like the families, one packet per run; they never wait and nothing waits for
 * them (an item and a section keep them by code). An ERP that gives none reads 0 and hands nothing.</li>
 * <li><b>Items.</b> Compared on what the import applies: name, description, VAT, active, ERP id, family and sub-family
 * (only when the ERP's one exists at the head office), the price with VAT at 3 decimals, discount group and maximum
 * discount (the import clears them); item variants (ZEN, step 3): the 14 variant fields ({@code ItemVariant}) and the
 * brand when the ERP gives one. The copies the engine makes of a head office item keep its variant fields and brand. A null or zero price makes the item inactive (the connector's mapper). Release
 * 2.2: the items page is read once per active stock point, in list order ({@link StockPoints}; none: the run does
 * nothing); every point's rows are written in full (new, changed, gone = inactive, never deleted), after the items; the
 * item gets its default copy from the first point where it is active, else the first that has it. A point that
 * answers no row keeps its rows and counts with them; a failed read fails the run before anything is applied. Items of
 * the ERP (erp_external_id set) in no point any more are handed inactive, never deleted, whatever their number; when a
 * point answered no row (a broken connection or a wrong code, never a cleanup) nothing is deactivated. Hand-made
 * items, packs made at the head office and TAX_STAMP have no ERP id and are never touched. A blank Description (the
 * name and the description) never replaces those of an item already at the head office; a new item takes its code as
 * name.</li>
 * <li><b>Barcodes.</b> One run goes on until it has caught up. A cursor on the position (the connector's: Entry_No for
 * navpospages), in the state table, read page after page until the ERP answers no row. It only moves over rows that
 * are at the head office (checked against its barcode table, again after each packet), left out (the connector's
 * reasons, item not at the head office) or replaced by a later row of the same barcode, and is saved after each
 * packet: it never passes a row that was not saved (such a row stops the run, the next run hands it again). New items
 * arriving after the barcodes started are kept in the state table ("needs its barcodes"); every one of them has its
 * barcodes read by item and applied first, in the same run; an item leaves that list once all its barcodes are at the
 * head office.</li>
 * <li><b>Dry run.</b> Reads, compares and summarises (the barcode run to the last page; the items run per stock point);
 * hands nothing; writes no row of a point; does not write the state table.</li>
 * </ul>
 * Code cleanup step 3: was NavPosPagesSync of the navpospages connector; the state table, its keys and every summary
 * text are unchanged. Exists with a connector that has the ability HEAD_OFFICE_STOCK_POINTS (the head office keeps its
 * points de stock and runs the single catalogue job); the connector gives the source and the settings.
 */
@Component
@ConditionalOnErpAbility(ErpAbility.HEAD_OFFICE_STOCK_POINTS)
public class CatalogueEngine {

	static final String ITEMS_RUN = "items.run";
	static final String ITEMS_PENDING = "items.pending";
	static final String BARCODE_CURSOR = "barcodes.cursor";
	static final String NEEDS_BARCODES = "needs-barcodes:";

	/** Item numbers per call of the barcodes of new items. */
	static final int ITEMS_PER_BARCODE_CALL = 20;
	/** The decimals of a clearance price before VAT: those of ho_stock_point_item.clearance_price (ZEN gives 5). */
	static final int PRICE_SCALE = 5;

	public static final String TAX_STAMP_ITEM = "tax stamp item (TAX_STAMP_ERP_ITEM_CODE)";
	public static final String ITEM_NOT_AT_HEAD_OFFICE = "item not at the head office";
	/** Kept as it was (the summary texts do not change): the lower position of the same barcode. */
	public static final String REPLACED_BY_LATER_ROW = "same barcode, lower Entry_No";

	static final String ITEMS_ALREADY_RUNNING = "an items run is already running: this one does not start";
	static final String BARCODES_ALREADY_RUNNING = "a barcode run is already running: this one does not start";

	/** Stock points, step 2: the items run reads the active points; without one it does nothing. */
	public static final String NO_STOCK_POINT = "No point de stock: create one in Catalogue > Points de stock, then run the"
			+ " job again.";

	/** The items whose family or sub-family is not among the ERP's categories named in the summary, at most. */
	static final int NOT_IN_CATEGORIES_SHOWN = 10;

	/**
	 * "family X" or "sub-family Y" when the item names one that is not among the categories the head office has from the
	 * ERP (the import then keeps the item without it, as before); null otherwise.
	 */
	static String missingCategory(ErpItemDTO item, Set<String> familyCodes, Set<String> subFamilyCodes) {
		String family = item.getFamilyExternalId();
		if (family != null && !family.trim().isEmpty() && !familyCodes.contains(family.trim())) {
			return "family " + family.trim();
		}
		String subFamily = item.getSubFamilyExternalId();
		if (subFamily != null && !subFamily.trim().isEmpty() && !subFamilyCodes.contains(subFamily.trim())) {
			return "sub-family " + subFamily.trim();
		}
		return null;
	}

	private final ErpCatalogueSource source;
	private final HeadOfficeCatalogue headOffice;
	private final StockPoints stockPoints;
	private final CatalogueState state;
	private final CatalogueSettings settings;
	private final CatalogueImport importer;
	/** One items run at a time (the scheduler and "Run now" may meet). */
	private final AtomicBoolean itemsRunning = new AtomicBoolean();
	/** One barcode run at a time. */
	private final AtomicBoolean barcodesRunning = new AtomicBoolean();
	/** 3.1.1 (item images): the NAV item numbers of the items the last items run handed (none in a dry run). */
	private volatile Set<String> changedNavItemNos = new TreeSet<>();

	@Autowired
	public CatalogueEngine(ErpCatalogueSource source, HeadOfficeCatalogue headOffice, StockPoints stockPoints,
			CatalogueState state, CatalogueSettings settings, CatalogueImport importer) {
		this(source, headOffice, stockPoints, state, settings, importer, RetryingCatalogueSource.SLEEP);
	}

	/** 3.1.0: the page reads retried once after an I/O error or a timeout, after this pause (none in the tests). */
	CatalogueEngine(ErpCatalogueSource source, HeadOfficeCatalogue headOffice, StockPoints stockPoints,
			CatalogueState state, CatalogueSettings settings, CatalogueImport importer,
			RetryingCatalogueSource.Pause pause) {
		this.source = new RetryingCatalogueSource(source, pause);
		this.headOffice = headOffice;
		this.stockPoints = stockPoints;
		this.state = state;
		this.settings = settings;
		this.importer = importer;
	}

	/** 3.1.1 (item images): the NAV item numbers of the items the last items run handed (new, changed, deactivated). */
	public Set<String> changedNavItemNos() {
		return changedNavItemNos;
	}

	/** The settings in effect (the job reads the dry run from them). */
	public CatalogueSettings getSettings() {
		return settings;
	}

	// ─── Families ───────────────────────────────────────────────

	public CatalogueRun<ErpItemFamilyDTO> families() {
		Part part = Part.CATEGORIES;
		RunSummary summary = new RunSummary("families", source.pageLabel(part), settings.dryRun());
		ErpRead<ErpItemFamilyDTO> read = source.categories().getFamilies();
		summary.read(read);
		Map<String, HeadOfficeCatalogue.Family> local = headOffice.families();
		List<ErpItemFamilyDTO> changes = new ArrayList<>();
		int fresh = 0;
		for (ErpItemFamilyDTO family : read.getRows()) {
			HeadOfficeCatalogue.Family here = local.get(family.getCode());
			if (here == null) {
				fresh++;
				changes.add(family);
			} else if (differs(family.getCode(), family.getName(), family.getDescription(), here)
					|| givenAndDifferent(family.getCategoryExternalId(), here.categoryCode)) {
				changes.add(family);
			}
		}
		changes.sort(Comparator.comparing(ErpItemFamilyDTO::getCode));
		List<ErpItemFamilyDTO> handed = summary.handOver(changes, fresh, cap());
		return run(handed, summary, part);
	}

	// ─── Sub-families ───────────────────────────────────────────

	public CatalogueRun<ErpItemSubFamilyDTO> subFamilies() {
		Part part = Part.CATEGORIES;
		RunSummary summary = new RunSummary("sub-families", source.pageLabel(part), settings.dryRun());
		ErpCategories categories = source.categories();
		String waiting = missingFamilies(categories, false);
		if (waiting != null) {
			return run(new ArrayList<>(), summary.waiting(waiting), part);
		}
		ErpRead<ErpItemSubFamilyDTO> read = categories.getSubFamilies();
		summary.read(read);
		Map<String, HeadOfficeCatalogue.SubFamily> local = headOffice.subFamilies();
		List<ErpItemSubFamilyDTO> changes = new ArrayList<>();
		int fresh = 0;
		for (ErpItemSubFamilyDTO subFamily : read.getRows()) {
			HeadOfficeCatalogue.SubFamily here = local.get(subFamily.getCode());
			if (here == null) {
				fresh++;
				changes.add(subFamily);
			} else if (differs(subFamily.getCode(), subFamily.getName(), subFamily.getDescription(), here)
					|| !Objects.equals(subFamily.getFamilyExternalId(), here.familyCode)) {
				changes.add(subFamily);
			}
		}
		changes.sort(Comparator.comparing(ErpItemSubFamilyDTO::getCode));
		List<ErpItemSubFamilyDTO> handed = summary.handOver(changes, fresh, cap());
		return run(handed, summary, part);
	}

	// ─── Catégories and sections (item variants, ZEN, step 4) ───

	/**
	 * The catégories (above the families), as the families: compared by code with the head office's (name, description,
	 * display order and active when the ERP gives them, ERP id), one packet handed per run. Never waits. An ERP that gives
	 * none (an empty read) hands nothing.
	 */
	public CatalogueRun<ErpItemCategoryDTO> categories() {
		Part part = Part.CATEGORIES;
		RunSummary summary = new RunSummary("categories", source.pageLabel(part), settings.dryRun());
		ErpRead<ErpItemCategoryDTO> read = source.categories().getCategories();
		summary.read(read);
		Map<String, HeadOfficeCatalogue.Level> local = headOffice.categories();
		List<ErpItemCategoryDTO> changes = new ArrayList<>();
		int fresh = 0;
		for (ErpItemCategoryDTO category : read.getRows()) {
			HeadOfficeCatalogue.Level here = local.get(category.getCode());
			if (here == null) {
				fresh++;
				changes.add(category);
			} else if (differs(category.getCode(), category.getName(), category.getDescription(),
					category.getDisplayOrder(), category.getActive(), category.getExternalId(), null, here)) {
				changes.add(category);
			}
		}
		changes.sort(Comparator.comparing(ErpItemCategoryDTO::getCode));
		List<ErpItemCategoryDTO> handed = summary.handOver(changes, fresh, cap());
		return run(handed, summary, part);
	}

	/**
	 * The sections (below the sub-families), as the catégories, their sub-family code compared too. Never waits: the
	 * sub-family is kept by code, a section is never held back for it.
	 */
	public CatalogueRun<ErpItemSectionDTO> sections() {
		Part part = Part.CATEGORIES;
		RunSummary summary = new RunSummary("sections", source.pageLabel(part), settings.dryRun());
		ErpRead<ErpItemSectionDTO> read = source.categories().getSections();
		summary.read(read);
		Map<String, HeadOfficeCatalogue.Level> local = headOffice.sections();
		List<ErpItemSectionDTO> changes = new ArrayList<>();
		int fresh = 0;
		for (ErpItemSectionDTO section : read.getRows()) {
			HeadOfficeCatalogue.Level here = local.get(section.getCode());
			if (here == null) {
				fresh++;
				changes.add(section);
			} else if (differs(section.getCode(), section.getName(), section.getDescription(), section.getDisplayOrder(),
					section.getActive(), section.getExternalId(), section.getSubFamilyExternalId(), here)) {
				changes.add(section);
			}
		}
		changes.sort(Comparator.comparing(ErpItemSectionDTO::getCode));
		List<ErpItemSectionDTO> handed = summary.handOver(changes, fresh, cap());
		return run(handed, summary, part);
	}

	/**
	 * A catégorie or a section against the head office's, on what the import applies: the name (the code when blank),
	 * the description (null = blank), the ERP id, the parent code (blank = none), the display order and the active flag
	 * when the ERP gives them (the import keeps the head office's otherwise).
	 */
	private static boolean differs(String code, String name, String description, Integer displayOrder, Boolean active,
			String erpExternalId, String parentCode, HeadOfficeCatalogue.Level here) {
		return !text(name, code).equals(text(here.name, null))
				|| !text(description, null).equals(text(here.description, null))
				|| !text(erpExternalId, null).equals(text(here.erpExternalId, null))
				|| !Objects.equals(blankToNull(parentCode), blankToNull(here.parentCode))
				|| (displayOrder != null && !displayOrder.equals(here.displayOrder))
				|| (active != null && active != !Boolean.FALSE.equals(here.active));
	}

	/** True when the ERP gives a value (not blank) that is not the head office's. */
	private static boolean givenAndDifferent(String erpValue, String headOfficeValue) {
		String given = blankToNull(erpValue);
		return given != null && !given.equals(blankToNull(headOfficeValue));
	}

	/**
	 * Null when every family (and, with subFamiliesToo, every sub-family) of this read of the categories is at the head
	 * office; otherwise the reason to wait. Read from the ERP page itself: no run of the families job has to be counted.
	 */
	private String missingFamilies(ErpCategories categories, boolean subFamiliesToo) {
		Set<String> families = headOffice.families().keySet();
		long missing = categories.getFamilies().getRows().stream().filter(f -> !families.contains(f.getCode())).count();
		if (missing > 0) {
			return "waiting for families: " + missing + " families of the ERP not at the head office yet";
		}
		if (subFamiliesToo) {
			Set<String> subFamilies = headOffice.subFamilies().keySet();
			missing = categories.getSubFamilies().getRows().stream().filter(f -> !subFamilies.contains(f.getCode()))
					.count();
			if (missing > 0) {
				return "waiting for sub-families: " + missing + " sub-families of the ERP not at the head office yet";
			}
		}
		return null;
	}

	/** Name (the import falls back to the code), description (null = blank), active, ERP id. */
	private static boolean differs(String code, String name, String description, HeadOfficeCatalogue.Family here) {
		return !text(name, code).equals(text(here.name, null)) || !text(description, null).equals(text(here.description, null))
				|| Boolean.FALSE.equals(here.active) || !code.equals(here.erpExternalId);
	}

	// ─── Items ──────────────────────────────────────────────────

	/**
	 * Reads, compares, then applies every change of the run (new, changed, deactivated) in packets of packet-size by
	 * code, each packet in its own transaction. A failed packet ends the run in error; the packets before it stay and the
	 * next run goes on with what is left (the head office tables are the memory). The rows applied are in the run, for
	 * the tests; the connector hands nothing more to the job.
	 */
	public CatalogueRun<ErpItemDTO> items() {
		if (!itemsRunning.compareAndSet(false, true)) {
			throw new ErpSyncWarningException(ITEMS_ALREADY_RUNNING);
		}
		long started = System.nanoTime();
		try {
			CatalogueRun<ErpItemDTO> run = readAndApplyItems();
			// 3.1.1 (item images): the NAV item numbers of the items handed, asked by the images part of this run
			Set<String> navItemNos = new TreeSet<>();
			if (!settings.dryRun()) {
				run.getHanded().stream().map(ErpItemDTO::getNavItemNo).filter(Objects::nonNull).map(String::trim)
						.filter(no -> !no.isEmpty()).forEach(navItemNos::add);
			}
			changedNavItemNos = navItemNos;
			// 3.1.0: one line per items step, so a run shows where its time went (equal: compared, nothing to write)
			int read = run.count("read");
			int equal = Math.max(0, read - run.count("new") - run.count("changed") - run.count("deactivated"));
			org.apache.logging.log4j.LogManager.getLogger(CatalogueEngine.class).info(
					"ERP catalogue items: {} rows read, {} equal, {} new, {} changed, {} deactivated, {} written in {} ms",
					read, equal, run.count("new"), run.count("changed"), run.count("deactivated"), run.count("applied"),
					(System.nanoTime() - started) / 1_000_000);
			return run;
		} finally {
			itemsRunning.set(false);
		}
	}

	private CatalogueRun<ErpItemDTO> readAndApplyItems() {
		Part part = Part.ITEMS;
		RunSummary summary = new RunSummary("items", source.pageLabel(part), settings.dryRun());
		List<StockPoints.Point> points = stockPoints.activePoints();
		if (points.isEmpty()) {
			return run(new ArrayList<>(), summary.waiting(NO_STOCK_POINT), part);
		}
		String waiting = missingFamilies(source.categories(), true);
		if (waiting != null) {
			return run(new ArrayList<>(), summary.waiting(waiting), part);
		}
		if (source.readsChangesOnly()) {
			return readAndApplyChanges(points, summary, part); // item variants (ZEN), step 6
		}
		// Every point read first, in list order: a failed read fails the run before anything is applied
		List<PointRead> reads = new ArrayList<>();
		for (StockPoints.Point point : points) {
			long start = System.nanoTime();
			ErpRead<ErpItemDTO> rows;
			try {
				rows = source.items(point.code);
			} catch (RuntimeException e) {
				recordRead(point, StockPoints.READ_FAILED, "read failed: " + e.getMessage());
				throw e;
			}
			PointRead read = new PointRead(point, rows.getRead(), rows, millisSince(start));
			summary.addRead(read.result);
			reads.add(read);
		}
		List<String> silent = reads.stream().filter(read -> read.read == 0).map(read -> read.point.code)
				.collect(Collectors.toList());
		boolean allSilent = silent.size() == reads.size();
		Map<String, HeadOfficeCatalogue.Item> local = headOffice.items();
		Set<String> familyCodes = headOffice.families().keySet();
		Set<String> subFamilyCodes = headOffice.subFamilies().keySet();
		String taxStamp = headOffice.taxStampErpCode();

		// The rows of each point that answered (compared before the items, whose blank names are filled below)
		for (PointRead read : reads) {
			read.stored = stockPoints.rows(read.point.id);
			if (read.read > 0) {
				read.compare(taxStamp, familyCodes, subFamilyCodes);
			}
		}

		// The item's default copy: the first point of the list where it is active, else the first that has it. A point
		// that answered no row counts with its last known rows (unless no point answered: nothing changes, as before).
		Map<String, ErpItemDTO> merged = new LinkedHashMap<>();
		for (PointRead read : reads) {
			List<ErpItemDTO> ofPoint = read.read > 0 ? read.result.getRows()
					: allSilent ? new ArrayList<>() : fromStored(read.stored, local);
			for (ErpItemDTO item : ofPoint) {
				ErpItemDTO first = merged.get(item.getCode());
				if (first == null || (!Boolean.TRUE.equals(first.getActive()) && Boolean.TRUE.equals(item.getActive()))) {
					merged.put(item.getCode(), item);
				}
			}
		}

		List<ErpItemDTO> changes = new ArrayList<>();
		Set<String> freshCodes = new HashSet<>();
		Set<String> inErp = new HashSet<>();
		int changed = 0;
		// An item whose family or sub-family is not among the ERP's categories is imported without it (as before): counted
		int notInCategories = 0;
		List<String> notInCategoriesFirst = new ArrayList<>();
		for (ErpItemDTO item : merged.values()) {
			inErp.add(item.getCode());
			if (item.getCode().equals(taxStamp)) {
				summary.leftOut(TAX_STAMP_ITEM); // the import never saves it: it would wait for ever
				continue;
			}
			String missing = missingCategory(item, familyCodes, subFamilyCodes);
			if (missing != null) {
				notInCategories++;
				if (notInCategoriesFirst.size() < NOT_IN_CATEGORIES_SHOWN) {
					notInCategoriesFirst.add(item.getCode() + " (" + missing + ")");
				}
			}
			HeadOfficeCatalogue.Item here = local.get(item.getCode());
			if (here == null) {
				freshCodes.add(item.getCode()); // a blank name: the import gives it the code
				changes.add(item);
				continue;
			}
			// A blank Description (name and description) never replaces the head office's: kept, not a change
			if (text(item.getName(), null).isEmpty()) {
				item.setName(here.name);
			}
			if (text(item.getDescription(), null).isEmpty()) {
				item.setDescription(here.description);
			}
			if (differs(item, here, familyCodes, subFamilyCodes)) {
				changed++;
				changes.add(item);
			}
		}

		// Items of the ERP in no point any more: inactive, never deleted, whatever their number; not when a point answered
		// no row (a broken connection or a wrong code, never a cleanup)
		List<ErpItemDTO> gone = new ArrayList<>();
		for (HeadOfficeCatalogue.Item here : local.values()) {
			if (here.fromErp() && here.isActive() && !here.code.equals(taxStamp) && !inErp.contains(here.code)) {
				gone.add(inactiveCopy(here));
			}
		}
		int deactivated = 0;
		if (!gone.isEmpty()) {
			if (allSilent) {
				summary.guard("the ERP answered no item for the location: " + gone.size()
						+ " items not deactivated");
			} else if (!silent.isEmpty()) {
				summary.guard("stock points that answered no item (" + String.join(", ", silent) + "): " + gone.size()
						+ " items not deactivated");
			} else {
				changes.addAll(gone);
				deactivated = gone.size();
			}
		}
		changes.sort(Comparator.comparing(ErpItemDTO::getCode));
		summary.put("new", freshCodes.size());
		summary.put("changed", changed);
		summary.put("deactivated", deactivated);
		if (notInCategories > 0) {
			summary.put("notInCategories", notInCategories);
			summary.put("notInCategoriesFirst", String.join(", ", notInCategoriesFirst)); // text: never a list in the log
		}
		if (settings.dryRun()) {
			summary.put("applied", 0);
			summary.put("packets", 0);
			summary.put("stockPoints", pointSummaries(reads));
			return run(new ArrayList<>(), summary, part);
		}

		// Counted before applying: while a packet of new items is not saved, the barcodes wait
		saveRun(ITEMS_RUN, ITEMS_PENDING, freshCodes.size());
		boolean barcodesStarted = cursor() > 0;
		int size = settings.packetSize();
		int packets = (changes.size() + size - 1) / size;
		List<ErpItemDTO> applied = new ArrayList<>();
		for (int from = 0; from < changes.size(); from += size) {
			List<ErpItemDTO> packet = new ArrayList<>(changes.subList(from, Math.min(from + size, changes.size())));
			// New items once the barcodes have started: their barcodes may be behind the cursor
			if (barcodesStarted) {
				for (ErpItemDTO item : packet) {
					if (freshCodes.contains(item.getCode())) {
						state.put(NEEDS_BARCODES + item.getCode(), "1");
					}
				}
			}
			try {
				importer.items(packet);
			} catch (RuntimeException e) {
				String message = "Items: packet " + (from / size + 1) + " of " + packets + " failed, " + applied.size()
						+ " of " + changes.size() + " rows applied before it (the next run goes on): " + e.getMessage();
				for (PointRead read : reads) {
					recordRead(read.point, StockPoints.READ_FAILED, "rows not written: " + message);
				}
				throw new IllegalStateException(message, e);
			}
			applied.addAll(packet);
		}
		summary.put("applied", applied.size());
		summary.put("packets", packets);
		if (!freshCodes.isEmpty()) {
			// Counted again once applied: the new items the import did not save (if any) keep the barcodes waiting
			Set<String> saved = headOffice.items().keySet();
			state.put(ITEMS_PENDING, String.valueOf(freshCodes.stream().filter(code -> !saved.contains(code)).count()));
		}
		// The rows of each point, after the items (a row needs its item), in packets by item code
		for (PointRead read : reads) {
			long start = System.nanoTime();
			int count = (read.toWrite.size() + size - 1) / size;
			for (int from = 0; from < read.toWrite.size(); from += size) {
				List<StockPoints.Row> packet = new ArrayList<>(
						read.toWrite.subList(from, Math.min(from + size, read.toWrite.size())));
				try {
					read.written += stockPoints.write(read.point.id, packet);
				} catch (RuntimeException e) {
					String message = "Stock point " + read.point.code + ": packet " + (from / size + 1) + " of " + count
							+ " failed, " + read.written + " of " + read.toWrite.size()
							+ " rows written before it (the next run goes on): " + e.getMessage();
					recordRead(read.point, StockPoints.READ_FAILED, message);
					throw new IllegalStateException(message, e);
				}
			}
			read.millis += millisSince(start);
		}
		summary.put("stockPoints", pointSummaries(reads));
		for (PointRead read : reads) {
			recordRead(read.point, read.read == 0 ? StockPoints.READ_NO_ANSWER : StockPoints.READ_OK,
					(read.read == 0 ? "no row: its rows kept; " : "") + "read " + read.read + ", new " + read.fresh
							+ ", changed " + read.changed + ", deactivated " + read.deactivated + ", written "
							+ read.written + ", " + read.millis + " ms");
		}
		return run(applied, summary, part);
	}

	// ─── Items, changes only (item variants, ZEN, step 6) ───────

	/**
	 * The checkpoint of a point de stock in the state table (key + point id): the highest ERP time (lastModifiedAt) of
	 * the rows of the point applied, ISO with its offset.
	 */
	static final String ITEMS_SINCE = "items.since:";

	/** The checkpoint of this point, or null (none, or not readable): its next read is a whole read. */
	public OffsetDateTime checkpoint(long pointId) {
		String value = state.get(ITEMS_SINCE + pointId);
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value.trim());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/** Forgets the checkpoint of this point: its next items run reads every row of it (nothing deactivated by absence). */
	public void resetCheckpoint(long pointId) {
		state.remove(ITEMS_SINCE + pointId);
	}

	/**
	 * The items run of a connector that reads changes only ({@link ErpCatalogueSource#readsChangesOnly()}):
	 * <ul>
	 * <li>Each active point is read from its checkpoint ({@link #checkpoint}; null: every row), in list order; a failed
	 * read fails the run before anything is applied. A point that answers no row has no change: a success.</li>
	 * <li>Nothing is deactivated by absence: a row or an item not answered stays as it is. A row answered inactive makes
	 * its point's row inactive; an item is active when one of its rows in the active points is (the rows answered, else the
	 * rows stored), so an item whose last active row is answered inactive becomes inactive, and an answered active row
	 * makes it active again. The item's other fields come from its first row answered active, else its first row answered
	 * (as the whole read); the compares of an item and of a row are those of the whole read.</li>
	 * <li>The codes answered are handled in packets of packet-size, by code: only the stored rows of a packet's codes are
	 * loaded ({@link StockPoints#rows(long, java.util.Collection)}), then the packet's items are applied, then its rows of
	 * each point. A failed packet of items ends the run in error. A point whose rows fail stops there (its later packets
	 * skipped); the other points go on and the run ends in error.</li>
	 * <li>The checkpoint of a point is written once all its rows are applied: the highest lastModifiedAt of the rows it
	 * answered (never earlier than the one it had); not when it failed, nor when it answered no dated row, nor in a dry
	 * run.</li>
	 * </ul>
	 */
	private CatalogueRun<ErpItemDTO> readAndApplyChanges(List<StockPoints.Point> points, RunSummary summary, Part part) {
		summary.put("changesOnly", true);
		List<ChangesRead> reads = new ArrayList<>();
		for (StockPoints.Point point : points) {
			OffsetDateTime since = checkpoint(point.id);
			long start = System.nanoTime();
			ErpRead<ErpItemDTO> rows;
			try {
				rows = source.items(point.code, since);
			} catch (RuntimeException e) {
				recordRead(point, StockPoints.READ_FAILED, "read failed: " + e.getMessage());
				throw e;
			}
			summary.addRead(rows);
			reads.add(new ChangesRead(point, since, rows, millisSince(start)));
		}
		Map<String, HeadOfficeCatalogue.Item> local = headOffice.items();
		Set<String> familyCodes = headOffice.families().keySet();
		Set<String> subFamilyCodes = headOffice.subFamilies().keySet();
		String taxStamp = headOffice.taxStampErpCode();

		TreeSet<String> answered = new TreeSet<>();
		reads.forEach(read -> answered.addAll(read.rows.keySet()));
		if (taxStamp != null && answered.remove(taxStamp)) {
			summary.leftOut(TAX_STAMP_ITEM); // the import never saves it: it would wait for ever
		}
		Set<String> freshCodes = answered.stream().filter(code -> !local.containsKey(code)).collect(Collectors.toSet());
		// Counted before applying: while a packet of new items is not saved, the barcodes wait
		saveRun(ITEMS_RUN, ITEMS_PENDING, freshCodes.size());
		boolean barcodesStarted = cursor() > 0;
		int size = settings.packetSize();
		List<String> codes = new ArrayList<>(answered);
		List<ErpItemDTO> applied = new ArrayList<>();
		int changed = 0;
		int deactivated = 0;
		int packets = 0;
		int notInCategories = 0;
		List<String> notInCategoriesFirst = new ArrayList<>();
		for (int from = 0; from < codes.size(); from += size) {
			List<String> packet = codes.subList(from, Math.min(from + size, codes.size()));
			// The stored rows of these codes only, in every active point
			Map<Long, Map<String, StockPoints.Row>> stored = new HashMap<>();
			for (StockPoints.Point point : points) {
				stored.put(point.id, stockPoints.rows(point.id, packet));
			}
			// The rows of each point, compared before the items (whose blank names are filled below)
			Map<Long, List<StockPoints.Row>> rowsToWrite = new HashMap<>();
			for (ChangesRead read : reads) {
				List<StockPoints.Row> toWrite = new ArrayList<>();
				rowsToWrite.put(read.point.id, toWrite);
				Map<String, StockPoints.Row> ofPoint = stored.get(read.point.id);
				for (String code : packet) {
					ErpItemDTO row = read.rows.get(code);
					if (row == null) {
						continue;
					}
					StockPoints.Row here = ofPoint.get(code);
					StockPoints.Row next = rowOf(row, here, familyCodes, subFamilyCodes);
					if (here == null) {
						read.fresh++;
						toWrite.add(next);
					} else if (differs(next, here)) {
						if (here.active && !next.active) {
							read.deactivated++;
						} else {
							read.changed++;
						}
						toWrite.add(next);
					}
				}
			}
			// The items: active when one of their rows in the active points is (answered, else stored)
			List<ErpItemDTO> itemChanges = new ArrayList<>();
			for (String code : packet) {
				ErpItemDTO item = null;
				boolean active = false;
				for (ChangesRead read : reads) {
					ErpItemDTO row = read.rows.get(code);
					if (row != null) {
						if (item == null || (!Boolean.TRUE.equals(item.getActive()) && Boolean.TRUE.equals(row.getActive()))) {
							item = row;
						}
						active |= Boolean.TRUE.equals(row.getActive());
					} else {
						StockPoints.Row kept = stored.get(read.point.id).get(code);
						active |= kept != null && kept.active;
					}
				}
				item.setActive(active);
				String missing = missingCategory(item, familyCodes, subFamilyCodes);
				if (missing != null) {
					notInCategories++;
					if (notInCategoriesFirst.size() < NOT_IN_CATEGORIES_SHOWN) {
						notInCategoriesFirst.add(code + " (" + missing + ")");
					}
				}
				HeadOfficeCatalogue.Item here = local.get(code);
				if (here == null) {
					itemChanges.add(item); // a blank name: the import gives it the code
					continue;
				}
				// A blank Description (name and description) never replaces the head office's: kept, not a change
				if (text(item.getName(), null).isEmpty()) {
					item.setName(here.name);
				}
				if (text(item.getDescription(), null).isEmpty()) {
					item.setDescription(here.description);
				}
				if (differs(item, here, familyCodes, subFamilyCodes)) {
					if (here.isActive() && !active) {
						deactivated++;
					} else {
						changed++;
					}
					itemChanges.add(item);
				}
			}
			if (settings.dryRun()) {
				continue;
			}
			if (!itemChanges.isEmpty()) {
				// New items once the barcodes have started: their barcodes may be behind the cursor
				if (barcodesStarted) {
					for (ErpItemDTO item : itemChanges) {
						if (freshCodes.contains(item.getCode())) {
							state.put(NEEDS_BARCODES + item.getCode(), "1");
						}
					}
				}
				try {
					importer.items(itemChanges);
				} catch (RuntimeException e) {
					String message = "Items: packet " + (from / size + 1) + " failed, " + applied.size()
							+ " rows applied before it (each point is read again from its checkpoint): " + e.getMessage();
					for (ChangesRead read : reads) {
						recordRead(read.point, StockPoints.READ_FAILED, "rows not written: " + message);
					}
					throw new IllegalStateException(message, e);
				}
				applied.addAll(itemChanges);
				packets++;
			}
			// The rows of each point, after the packet's items (a row needs its item)
			for (ChangesRead read : reads) {
				List<StockPoints.Row> toWrite = rowsToWrite.get(read.point.id);
				if (read.failed != null || toWrite.isEmpty()) {
					continue;
				}
				long start = System.nanoTime();
				try {
					read.written += stockPoints.write(read.point.id, toWrite);
				} catch (RuntimeException e) {
					read.failed = "Stock point " + read.point.code + ": packet " + (from / size + 1) + " failed, "
							+ read.written + " rows written before it (its checkpoint kept, the next run reads it again): "
							+ e.getMessage();
					recordRead(read.point, StockPoints.READ_FAILED, read.failed);
				}
				read.millis += millisSince(start);
			}
		}
		if (!settings.dryRun() && !freshCodes.isEmpty()) {
			// Counted again once applied: the new items the import did not save (if any) keep the barcodes waiting
			Set<String> saved = headOffice.items().keySet();
			state.put(ITEMS_PENDING, String.valueOf(freshCodes.stream().filter(code -> !saved.contains(code)).count()));
		}
		// The checkpoint of each point whose rows were all applied
		List<String> failures = new ArrayList<>();
		for (ChangesRead read : reads) {
			if (read.failed != null) {
				failures.add(read.failed);
				continue;
			}
			if (!settings.dryRun() && read.maxSeen != null) {
				read.checkpoint = read.since != null && read.since.isAfter(read.maxSeen) ? read.since : read.maxSeen;
				state.put(ITEMS_SINCE + read.point.id, read.checkpoint.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
			}
			recordRead(read.point, StockPoints.READ_OK, "read " + read.read + " since " + dateText(read.since) + ", new "
					+ read.fresh + ", changed " + read.changed + ", deactivated " + read.deactivated + ", written "
					+ read.written + ", checkpoint " + dateText(read.checkpoint) + ", " + read.millis + " ms");
		}
		summary.put("new", freshCodes.size());
		summary.put("changed", changed);
		summary.put("deactivated", deactivated);
		if (notInCategories > 0) {
			summary.put("notInCategories", notInCategories);
			summary.put("notInCategoriesFirst", String.join(", ", notInCategoriesFirst)); // text: never a list in the log
		}
		summary.put("applied", applied.size());
		summary.put("packets", packets);
		summary.put("stockPoints", changesSummaries(reads));
		if (!failures.isEmpty()) {
			throw new IllegalStateException(String.join("; ", failures));
		}
		return run(applied, summary, part);
	}

	/** One point's read of changes: its rows by code, the time it was read from, the highest time read. */
	private static final class ChangesRead {
		final StockPoints.Point point;
		/** The checkpoint the point was read from; null: a whole read. */
		final OffsetDateTime since;
		final int read;
		/** The rows answered, by item code. */
		final Map<String, ErpItemDTO> rows = new LinkedHashMap<>();
		/** The highest lastModifiedAt of the rows answered; null when none has one. */
		final OffsetDateTime maxSeen;
		long millis;
		int fresh;
		int changed;
		int deactivated;
		int written;
		/** Why the point stopped (a packet of its rows failed); null when all its rows were applied. */
		String failed;
		/** The checkpoint written by this run; null: none written. */
		OffsetDateTime checkpoint;

		ChangesRead(StockPoints.Point point, OffsetDateTime since, ErpRead<ErpItemDTO> result, long millis) {
			this.point = point;
			this.since = since;
			this.read = result.getRead();
			this.millis = millis;
			OffsetDateTime highest = null;
			for (ErpItemDTO row : result.getRows()) {
				rows.put(row.getCode(), row);
				OffsetDateTime at = row.getLastModifiedAt();
				if (at != null && (highest == null || at.isAfter(highest))) {
					highest = at;
				}
			}
			this.maxSeen = highest;
		}
	}

	/** The stockPoints part of the summary of a run of changes: per point code, in list order. */
	private static Map<String, Object> changesSummaries(List<ChangesRead> reads) {
		Map<String, Object> points = new LinkedHashMap<>();
		for (ChangesRead read : reads) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("name", read.point.name);
			values.put("since", dateText(read.since));
			values.put("read", read.read);
			values.put("maxSeen", dateText(read.maxSeen));
			values.put("new", read.fresh);
			values.put("changed", read.changed);
			values.put("deactivated", read.deactivated);
			values.put("written", read.written);
			values.put("checkpoint", dateText(read.checkpoint));
			values.put("checkpointWritten", read.checkpoint != null);
			if (read.failed != null) {
				values.put("failed", read.failed);
			}
			values.put("durationMs", read.millis);
			points.put(read.point.code, values);
		}
		return points;
	}

	/** ISO with its offset; null stays null (text: the summary goes to the log as JSON). */
	private static String dateText(OffsetDateTime value) {
		return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}

	/** Step 4: the point's last read, for the points page; never in a dry run; a failure to record is only logged. */
	private void recordRead(StockPoints.Point point, String status, String summary) {
		if (settings.dryRun()) {
			return;
		}
		try {
			stockPoints.recordRead(point.id, status, summary);
		} catch (RuntimeException e) {
			org.apache.logging.log4j.LogManager.getLogger(CatalogueEngine.class)
					.warn("Stock point {}: last read not recorded ({})", point.code, e.getMessage());
		}
	}

	/** One point's read, its rows as the head office has them, and the rows to write. */
	private final class PointRead {
		final StockPoints.Point point;
		/** Rows the ERP answered for the point (0: no answer, nothing changes in the point). */
		final int read;
		final ErpRead<ErpItemDTO> result;
		long millis;
		Map<String, StockPoints.Row> stored = new LinkedHashMap<>();
		final List<StockPoints.Row> toWrite = new ArrayList<>();
		int fresh;
		int changed;
		int deactivated;
		int written;

		PointRead(StockPoints.Point point, int read, ErpRead<ErpItemDTO> result, long millis) {
			this.point = point;
			this.read = read;
			this.result = result;
			this.millis = millis;
		}

		/** New and changed rows; the active rows no longer answered, inactive (never deleted). By item code. */
		void compare(String taxStamp, Set<String> familyCodes, Set<String> subFamilyCodes) {
			Set<String> answered = new HashSet<>();
			for (ErpItemDTO item : result.getRows()) {
				answered.add(item.getCode());
				if (item.getCode().equals(taxStamp)) {
					continue; // the import never saves it
				}
				StockPoints.Row here = stored.get(item.getCode());
				StockPoints.Row row = rowOf(item, here, familyCodes, subFamilyCodes);
				if (here == null) {
					fresh++;
					toWrite.add(row);
				} else if (differs(row, here)) {
					changed++;
					toWrite.add(row);
				}
			}
			for (StockPoints.Row here : stored.values()) {
				if (here.active && !answered.contains(here.itemCode)) {
					deactivated++;
					toWrite.add(here.inactive());
				}
			}
			toWrite.sort(Comparator.comparing(row -> row.itemCode));
		}
	}

	/**
	 * The point's row for an item of its read. A blank Description (name and description) keeps those of the row; a new
	 * row with a blank name takes the item code, as the import does for a new item. A family or sub-family of the ERP
	 * that is not at the head office keeps the row's one, as the import does for the item.
	 */
	private static StockPoints.Row rowOf(ErpItemDTO item, StockPoints.Row here, Set<String> familyCodes,
			Set<String> subFamilyCodes) {
		String name = text(item.getName(), null);
		if (name.isEmpty()) {
			name = here != null ? here.name : item.getCode();
		}
		String description = text(item.getDescription(), null);
		if (description.isEmpty() && here != null) {
			description = text(here.description, null);
		}
		return new StockPoints.Row(item.getCode(), name, description.isEmpty() ? null : description,
				known(item.getFamilyExternalId(), familyCodes, here == null ? null : here.familyCode),
				known(item.getSubFamilyExternalId(), subFamilyCodes, here == null ? null : here.subFamilyCode),
				item.getUnitPrice() == null ? 0.0 : item.getUnitPrice().doubleValue(), Boolean.TRUE.equals(item.getActive()),
				Boolean.TRUE.equals(item.getClearance()), item.getClearancePrice());
	}

	/** The ERP's code when the head office has it, else the code kept. */
	private static String known(String erpCode, Set<String> headOfficeCodes, String kept) {
		String code = blankToNull(erpCode);
		return code != null && headOfficeCodes.contains(code) ? code : blankToNull(kept);
	}

	/**
	 * Name, description, family, sub-family, active, the price with VAT at 3 decimals. Item variants (ZEN), step 5: the
	 * clearance flag (a null column read as false, an ERP without it gives false) and the clearance price before VAT at
	 * {@link #PRICE_SCALE} decimals (null = no price).
	 */
	private boolean differs(StockPoints.Row row, StockPoints.Row here) {
		Integer vat = settings.defaultVat();
		return !text(row.name, null).equals(text(here.name, null))
				|| !text(row.description, null).equals(text(here.description, null))
				|| !Objects.equals(blankToNull(row.familyCode), blankToNull(here.familyCode))
				|| !Objects.equals(blankToNull(row.subFamilyCode), blankToNull(here.subFamilyCode))
				|| row.active != here.active || withVat(price(row), vat).compareTo(withVat(price(here), vat)) != 0
				|| row.clearance != here.clearance || !samePrice(row.clearancePrice, here.clearancePrice);
	}

	/** Both null, or equal at {@link #PRICE_SCALE} decimals (HALF_UP). */
	static boolean samePrice(BigDecimal a, BigDecimal b) {
		if (a == null || b == null) {
			return a == null && b == null;
		}
		return a.setScale(PRICE_SCALE, RoundingMode.HALF_UP)
				.compareTo(b.setScale(PRICE_SCALE, RoundingMode.HALF_UP)) == 0;
	}

	private static BigDecimal price(StockPoints.Row row) {
		return row.unitPrice == null ? BigDecimal.ZERO : BigDecimal.valueOf(row.unitPrice);
	}

	/**
	 * A point's last known rows as items of the ERP, for the merge when the point answered no row. Item variants (ZEN),
	 * step 3: the variant fields and the brand are those of the head office item (a row has none), so that such a copy
	 * never empties them.
	 */
	private List<ErpItemDTO> fromStored(Map<String, StockPoints.Row> stored, Map<String, HeadOfficeCatalogue.Item> local) {
		List<ErpItemDTO> items = new ArrayList<>();
		for (StockPoints.Row row : stored.values()) {
			ErpItemDTO item = new ErpItemDTO();
			item.setExternalId(row.itemCode);
			item.setCode(row.itemCode);
			item.setName(row.name);
			item.setDescription(row.description);
			item.setFamilyExternalId(row.familyCode);
			item.setSubFamilyExternalId(row.subFamilyCode);
			item.setDefaultVAT(settings.defaultVat());
			item.setUnitPrice(price(row));
			item.setActive(row.active);
			HeadOfficeCatalogue.Item here = local.get(row.itemCode);
			if (here != null) {
				here.variant.copyTo(item);
				item.setBrand(here.brand);
			}
			items.add(item);
		}
		return items;
	}

	/** The stockPoints part of the summary: per point code, in list order. */
	private static Map<String, Object> pointSummaries(List<PointRead> reads) {
		Map<String, Object> points = new LinkedHashMap<>();
		for (PointRead read : reads) {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("name", read.point.name);
			values.put("read", read.read);
			if (read.read == 0) {
				values.put("noAnswer", true); // its rows kept as they are
			}
			values.put("new", read.fresh);
			values.put("changed", read.changed);
			values.put("deactivated", read.deactivated);
			values.put("written", read.written);
			values.put("durationMs", read.millis);
			points.put(read.point.code, values);
		}
		return points;
	}

	private static long millisSince(long start) {
		return (System.nanoTime() - start) / 1_000_000;
	}

	private static String blankToNull(String value) {
		String trimmed = value == null ? "" : value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/**
	 * The fields the import applies, the price with VAT at 3 decimals. Item variants (ZEN), step 3: also the 14 variant
	 * fields ({@link ItemVariant}, blank as null; all null on both sides for an ERP without variants) and the brand when
	 * the ERP gives one (the import keeps the head office's otherwise).
	 */
	private static boolean differs(ErpItemDTO item, HeadOfficeCatalogue.Item here, Set<String> familyCodes,
			Set<String> subFamilyCodes) {
		if (!text(item.getName(), item.getCode()).equals(text(here.name, null))
				|| !text(item.getDescription(), null).equals(text(here.description, null))
				|| !Objects.equals(item.getDefaultVAT(), here.defaultVat) || item.getActive() != here.isActive()
				|| !item.getCode().equals(here.erpExternalId) || !text(here.itemDiscGroup, null).isEmpty()
				|| here.maximumAuthorizedDiscount != null) {
			return true;
		}
		if (!ItemVariant.of(item).equals(here.variant)) {
			return true;
		}
		String brand = text(item.getBrand(), null);
		if (!brand.isEmpty() && !brand.equals(text(here.brand, null))) {
			return true;
		}
		if (withVat(item.getUnitPrice(), item.getDefaultVAT())
				.compareTo(withVat(here.unitPrice == null ? BigDecimal.ZERO : BigDecimal.valueOf(here.unitPrice),
						here.defaultVat)) != 0) {
			return true;
		}
		// The import keeps the current family when the ERP's one is not at the head office: compared only when it is
		String family = item.getFamilyExternalId();
		if (family != null && familyCodes.contains(family) && !family.equals(here.familyCode)) {
			return true;
		}
		String subFamily = item.getSubFamilyExternalId();
		return subFamily != null && subFamilyCodes.contains(subFamily) && !subFamily.equals(here.subFamilyCode);
	}

	/** price x (1 + VAT/100), 3 decimals, HALF_UP. */
	static BigDecimal withVat(BigDecimal price, Integer vat) {
		BigDecimal rate = BigDecimal.ONE.add(BigDecimal.valueOf(vat == null ? 0 : vat).movePointLeft(2));
		return (price == null ? BigDecimal.ZERO : price).multiply(rate).setScale(3, RoundingMode.HALF_UP);
	}

	/** The head office item as it is, inactive (its variant fields and brand kept, step 3). */
	private static ErpItemDTO inactiveCopy(HeadOfficeCatalogue.Item here) {
		ErpItemDTO item = new ErpItemDTO();
		item.setExternalId(here.erpExternalId.trim());
		item.setCode(here.code);
		item.setName(here.name);
		item.setDescription(here.description);
		item.setUnitPrice(here.unitPrice == null ? null : BigDecimal.valueOf(here.unitPrice));
		item.setDefaultVAT(here.defaultVat);
		item.setItemDiscGroup(here.itemDiscGroup);
		item.setMaximumAuthorizedDiscount(here.maximumAuthorizedDiscount);
		item.setFamilyExternalId(here.familyCode);
		item.setSubFamilyExternalId(here.subFamilyCode);
		here.variant.copyTo(item);
		item.setBrand(here.brand);
		item.setActive(Boolean.FALSE);
		return item;
	}

	// ─── Barcodes ───────────────────────────────────────────────

	/**
	 * One run goes on until it has caught up, applying its changes itself in packets of packet-size, each packet in its
	 * own transaction ({@link CatalogueImport#barcodes}): first every item of the "needs its barcodes" list, then the
	 * cursor page after page until the ERP answers no row. After each packet the head office barcode table is read again:
	 * the cursor is saved only over rows that are there, and a row the import did not save stops the run (the next run
	 * hands it again). A failed packet ends the run in error; the packets before it and the cursor saved after them stay.
	 * The rows applied are in the run, for the tests; the connector hands nothing more to the job.
	 */
	public CatalogueRun<ErpItemBarcodeDTO> barcodes() {
		if (!barcodesRunning.compareAndSet(false, true)) {
			throw new ErpSyncWarningException(BARCODES_ALREADY_RUNNING);
		}
		try {
			return readAndApplyBarcodes();
		} finally {
			barcodesRunning.set(false);
		}
	}

	private CatalogueRun<ErpItemBarcodeDTO> readAndApplyBarcodes() {
		Part part = Part.BARCODES;
		RunSummary summary = new RunSummary("barcodes", source.pageLabel(part), settings.dryRun());
		String waiting = waitingFor(ITEMS_RUN, ITEMS_PENDING, "items");
		if (waiting != null) {
			summary.waiting(waiting);
			summary.values.remove("handed");
			summary.put("applied", 0);
			summary.put("packets", 0);
			summary.put("caughtUp", false);
			return run(new ArrayList<>(), summary, part);
		}
		Set<String> itemCodes = headOffice.items().keySet();
		BarcodePackets packets = new BarcodePackets();

		// 1. The barcodes of the new items that arrived after the cursor started, every item of the list
		List<String> needing = new ArrayList<>();
		for (String key : state.keysStartingWith(NEEDS_BARCODES)) {
			String code = key.substring(NEEDS_BARCODES.length());
			if (itemCodes.contains(code)) {
				needing.add(code);
			}
		}
		summary.put("itemsNeedingBarcodes", needing.size());
		int forNewItems = 0;
		for (int from = 0; from < needing.size(); from += ITEMS_PER_BARCODE_CALL) {
			List<String> batch = needing.subList(from, Math.min(from + ITEMS_PER_BARCODE_CALL, needing.size()));
			ErpRead<ErpBarcodeRow> read = source.barcodesOfItems(batch);
			Classified classified = classify(read, read.getRows(), itemCodes, summary);
			Set<String> notDone = new HashSet<>();
			for (int at = 0; at < classified.toHand.size(); at += cap()) {
				List<ErpBarcodeRow> packet = classified.toHand.subList(at,
						Math.min(at + cap(), classified.toHand.size()));
				List<ErpBarcodeRow> unsaved = packets.apply(packet);
				forNewItems += packet.size() - unsaved.size();
				unsaved.forEach(row -> notDone.add(row.getBarcode().getItemExternalId()));
			}
			for (String code : batch) {
				if (!notDone.contains(code) && !settings.dryRun()) {
					state.remove(NEEDS_BARCODES + code); // every barcode of the item is at the head office
				}
			}
		}
		if (forNewItems > 0) {
			summary.put("appliedForNewItems", forNewItems);
		}

		// 2. The cursor on the position (Entry_No), page after page until the ERP answers no row
		long cursor = cursor();
		summary.put("cursorFrom", cursor);
		int pages = 0;
		boolean caughtUp = false;
		reading: while (true) {
			ErpRead<ErpBarcodeRow> read = source.barcodesAfter(cursor);
			pages++;
			if (read.getRead() == 0) {
				caughtUp = true;
				break;
			}
			// Every row read counts for the cursor, left out ones included
			List<Long> positions = read.getPositions();
			long highest = positions.stream().mapToLong(CatalogueEngine::position).max().orElse(0);
			if (highest <= cursor) {
				summary.guard("the ERP answered rows at or below the cursor " + cursor + ": cursor kept");
				break;
			}
			List<ErpBarcodeRow> rows = new ArrayList<>(read.getRows());
			rows.sort(BY_POSITION);
			List<ErpBarcodeRow> toHand = classify(read, rows, itemCodes, summary).toHand;
			for (int at = 0; at < toHand.size(); at += cap()) {
				List<ErpBarcodeRow> unsaved = packets.apply(toHand.subList(at, Math.min(at + cap(), toHand.size())));
				if (!unsaved.isEmpty()) {
					// Never past a row the import did not save: the next run hands it again
					cursor = passBefore(positions, cursor, position(unsaved.get(0)));
					saveCursor(cursor);
					summary.guard(unsaved.size() + " barcodes handed but not at the head office after their packet: run"
							+ " stopped, cursor kept before them");
					break reading;
				}
				// Saved after each packet, only over the rows before the next row still to save
				cursor = at + cap() < toHand.size() ? passBefore(positions, cursor, position(toHand.get(at + cap())))
						: highest;
				saveCursor(cursor);
			}
			cursor = highest; // every row of the page is at the head office or left out
			saveCursor(cursor);
		}
		summary.put("pagesRead", pages);
		summary.put("cursorTo", cursor);
		summary.put("caughtUp", caughtUp);
		summary.put("applied", packets.applied.size());
		summary.put("packets", packets.count);
		if (settings.dryRun()) {
			summary.put("toApply", packets.toApply);
		}
		return run(packets.applied, summary, part);
	}

	/** The highest position of the page below limit (every row read, left out ones included), or the cursor if higher. */
	private static long passBefore(List<Long> positions, long cursor, long limit) {
		long passed = cursor;
		for (Long value : positions) {
			long position = position(value);
			if (position < limit) {
				passed = Math.max(passed, position);
			}
		}
		return passed;
	}

	private void saveCursor(long cursor) {
		if (!settings.dryRun()) {
			state.put(BARCODE_CURSOR, String.valueOf(cursor));
		}
	}

	/** The packets of one barcode run: applied one by one, each checked against the head office barcode table. */
	private final class BarcodePackets {
		final List<ErpItemBarcodeDTO> applied = new ArrayList<>();
		int count;
		int toApply;

		/**
		 * Applies the rows (by position) as one packet; returns those not at the head office afterwards, by position
		 * (none in a dry run, where nothing is applied).
		 */
		List<ErpBarcodeRow> apply(List<ErpBarcodeRow> rows) {
			if (rows.isEmpty()) {
				return new ArrayList<>();
			}
			if (settings.dryRun()) {
				toApply += rows.size();
				return new ArrayList<>();
			}
			// New objects for the import, as the mapper built them before: the rows read stay as read
			List<ErpItemBarcodeDTO> packet = rows.stream().map(row -> copy(row.getBarcode())).collect(Collectors.toList());
			try {
				importer.barcodes(packet);
			} catch (RuntimeException e) {
				throw new IllegalStateException("Barcodes: packet " + (count + 1) + " failed, " + applied.size()
						+ " rows applied before it (the next run goes on): " + e.getMessage(), e);
			}
			count++;
			Map<String, HeadOfficeCatalogue.Barcode> here = headOffice.barcodes(
					rows.stream().map(row -> row.getBarcode().getBarcode()).collect(Collectors.toList()));
			List<ErpBarcodeRow> unsaved = new ArrayList<>();
			for (ErpBarcodeRow row : rows) {
				if (!isAtHeadOffice(row, here.get(row.getBarcode().getBarcode()))) {
					unsaved.add(row);
				}
			}
			for (ErpItemBarcodeDTO dto : packet) {
				if (unsaved.stream().noneMatch(row -> row.getBarcode().getBarcode().equals(dto.getBarcode()))) {
					applied.add(dto);
				}
			}
			return unsaved;
		}
	}

	private static ErpItemBarcodeDTO copy(ErpItemBarcodeDTO read) {
		ErpItemBarcodeDTO barcode = new ErpItemBarcodeDTO();
		barcode.setExternalId(read.getExternalId());
		barcode.setBarcode(read.getBarcode());
		barcode.setItemExternalId(read.getItemExternalId());
		return barcode;
	}

	/** The row's barcode is at the head office, on the row's item and active. */
	private static boolean isAtHeadOffice(ErpBarcodeRow row, HeadOfficeCatalogue.Barcode saved) {
		return saved != null && row.getBarcode().getItemExternalId().equals(saved.itemCode)
				&& !Boolean.FALSE.equals(saved.active);
	}

	/**
	 * The rows of a read: left out (counted: the connector's reasons, then the item not at the head office and the same
	 * barcode at a lower position), already at the head office, or to hand over (by position).
	 */
	private Classified classify(ErpRead<ErpBarcodeRow> read, List<ErpBarcodeRow> rows, Set<String> itemCodes,
			RunSummary summary) {
		summary.add("read", read.getRead());
		read.getLeftOut().forEach((reason, count) -> summary.leftOut.merge(reason, count, Integer::sum));
		// The same barcode twice: the highest position is the one that counts
		Map<String, ErpBarcodeRow> latest = new LinkedHashMap<>();
		for (ErpBarcodeRow row : rows) {
			String barcode = row.getBarcode().getBarcode();
			if (!itemCodes.contains(row.getBarcode().getItemExternalId())) {
				summary.leftOut(ITEM_NOT_AT_HEAD_OFFICE);
			} else {
				ErpBarcodeRow other = latest.get(barcode);
				if (other != null) {
					summary.leftOut(REPLACED_BY_LATER_ROW);
				}
				if (other == null || position(row) > position(other)) {
					latest.put(barcode, row);
				}
			}
		}
		Map<String, HeadOfficeCatalogue.Barcode> here = latest.isEmpty() ? new LinkedHashMap<>()
				: headOffice.barcodes(latest.keySet());
		Classified classified = new Classified();
		for (Map.Entry<String, ErpBarcodeRow> entry : latest.entrySet()) {
			if (isAtHeadOffice(entry.getValue(), here.get(entry.getKey()))) {
				summary.add("alreadyAtHeadOffice", 1);
			} else {
				classified.toHand.add(entry.getValue());
			}
		}
		classified.toHand.sort(BY_POSITION);
		return classified;
	}

	private static final class Classified {
		final List<ErpBarcodeRow> toHand = new ArrayList<>();
	}

	/** By position, a row without one first (as by Entry_No before). */
	private static final Comparator<ErpBarcodeRow> BY_POSITION = Comparator.comparing(ErpBarcodeRow::getPosition,
			Comparator.nullsFirst(Long::compare));

	private static long position(ErpBarcodeRow row) {
		return position(row.getPosition());
	}

	/** A row without a position counts as 0 (as a row without Entry_No before). */
	private static long position(Long value) {
		return value == null ? 0 : value;
	}

	private long cursor() {
		return number(state.get(BARCODE_CURSOR));
	}

	// ─── State and summary ──────────────────────────────────────

	/** Null when the previous kind ran and has nothing left to save; otherwise the reason to wait. */
	private String waitingFor(String runKey, String pendingKey, String what) {
		if (!"true".equals(state.get(runKey))) {
			return "waiting for " + what + ": no " + what + " run yet";
		}
		long pending = number(state.get(pendingKey));
		return pending > 0 ? "waiting for " + what + ": " + pending + " new " + what + " not at the head office yet" : null;
	}

	private void saveRun(String runKey, String pendingKey, int fresh) {
		if (!settings.dryRun()) {
			state.put(runKey, "true");
			state.put(pendingKey, String.valueOf(fresh));
		}
	}

	/** Rows per packet. Families and sub-families: one packet per run, the rest at the next runs. */
	private int cap() {
		return settings.packetSize();
	}

	private <T> CatalogueRun<T> run(List<T> handed, RunSummary summary, Part part) {
		return new CatalogueRun<>(settings.dryRun() ? new ArrayList<>() : handed, summary.values, source.pageUrl(part));
	}

	private static long number(String value) {
		try {
			return value == null ? 0 : Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** Trimmed text, the fallback when blank, "" when both are blank. */
	private static String text(String value, String fallback) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isEmpty() && fallback != null) {
			return fallback.trim();
		}
		return trimmed;
	}
}
