package com.digithink.zsretail.headoffice.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.CatalogueBarcodeCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueCategoryCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueFamilyCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueItemCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueSectionCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueSubFamilyCopyDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemComposition;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemCompositionRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.core.service.CatalogueCodeChangeException;
import com.digithink.zsretail.core.service.CatalogueCodeTooLongException;
import com.digithink.zsretail.core.service.CatalogueHeadOfficeHooks;
import com.digithink.zsretail.headoffice.model.HoPriceListLine;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoPriceListLineRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 6.1: the catalogue of a head office without an ERP on the copies down mechanism (domain
 * CATALOGUE). Families, sub-families, items and barcodes, every record for every store, record codes with a prefix
 * ({@link CatalogueKind}). The item copy carries the price worked out for the store that pulls (task 6.4): the line of
 * its selling price list for the item, otherwise the base price (item.unitPrice). Every save, delete, pack change and
 * data import of a catalogue record is one change for every store. The system item TAX_STAMP never travels. Release
 * 2.2 (stock points, head office whose catalogue comes from the ERP): a store receives an ERP item only when its point
 * has a row for it, built from that row; a store with no point receives no ERP item and no ERP barcode; the selling
 * price lists are not used there. Stock point copies, step 4: there, an ERP item and its barcodes are recorded for the
 * stores whose point has a row for the item (active or not), never for every store; none when no point has it. A head
 * office with a manual catalogue keeps the rules above. Item variants (ZEN), step 7: the catégories (CATEGORY:) and the
 * sections (SECTION:) go to every store, as the families; the item copy carries the 14 variant fields. See
 * docs/modules/head-office.md, "Catalogue owned by the head office" and "Stock points".
 */
@Service
@ConditionalOnInstallation(Installation.HEAD_OFFICE_WITHOUT_ERP)
@Log4j2
public class HoCatalogueService implements DownDomainProvider, CatalogueHeadOfficeHooks {

	/** Copies hold no date: a plain mapper. */
	static final ObjectMapper COPY_MAPPER = new ObjectMapper();

	/** Changes recorded per transaction after a data import. */
	static final int IMPORT_CHUNK = 500;

	private final ItemFamilyRepository families;
	private final ItemSubFamilyRepository subFamilies;
	private final ItemRepository items;
	private final ItemBarcodeRepository barcodes;
	private final ItemCompositionRepository compositions;
	private final HoPriceListLineRepository priceLines;

	/** Looked up at the call: the feed itself collects the providers, this one included. */
	private final Supplier<CopiesDownFeed> feed;
	private final TransactionOperations writeTransactions;

	/**
	 * Stock points (release 2.2): the rows of the points, given only on a head office whose catalogue comes from the ERP
	 * (null on a head office with a manual catalogue and in the tests of step 6). Not null means that head office: every
	 * copy comes from the points' rows and the selling price lists are not used.
	 */
	private final HoStockPointItemRepository stockPointItems;
	private final StoreRepository stores;
	/** Item variants (ZEN), step 7: the catégories and the sections (null in the tests that have none). */
	private final ItemCategoryRepository categories;
	private final ItemSectionRepository sections;

	@Autowired
	public HoCatalogueService(ItemFamilyRepository families, ItemSubFamilyRepository subFamilies, ItemRepository items,
			ItemBarcodeRepository barcodes, ItemCompositionRepository compositions, HoPriceListLineRepository priceLines,
			ObjectProvider<CopiesDownFeed> feed, PlatformTransactionManager transactionManager,
			HoStockPointItemRepository stockPointItems, StoreRepository stores, ApplicationModeService mode,
			ItemCategoryRepository categories, ItemSectionRepository sections) {
		this(families, subFamilies, items, barcodes, compositions, priceLines, (Supplier<CopiesDownFeed>) feed::getObject,
				new TransactionTemplate(transactionManager), mode.isErpCatalogueOnly() ? stockPointItems : null, stores,
				categories, sections);
	}

	/** With given collaborators, without stock points: used by the tests. */
	public HoCatalogueService(ItemFamilyRepository families, ItemSubFamilyRepository subFamilies, ItemRepository items,
			ItemBarcodeRepository barcodes, ItemCompositionRepository compositions, HoPriceListLineRepository priceLines,
			Supplier<CopiesDownFeed> feed, TransactionOperations writeTransactions) {
		this(families, subFamilies, items, barcodes, compositions, priceLines, feed, writeTransactions, null, null);
	}

	/** With given collaborators and stock points, without catégories and sections: used by the tests. */
	public HoCatalogueService(ItemFamilyRepository families, ItemSubFamilyRepository subFamilies, ItemRepository items,
			ItemBarcodeRepository barcodes, ItemCompositionRepository compositions, HoPriceListLineRepository priceLines,
			Supplier<CopiesDownFeed> feed, TransactionOperations writeTransactions,
			HoStockPointItemRepository stockPointItems, StoreRepository stores) {
		this(families, subFamilies, items, barcodes, compositions, priceLines, feed, writeTransactions, stockPointItems,
				stores, null, null);
	}

	/** With given collaborators, stock points, catégories and sections. */
	public HoCatalogueService(ItemFamilyRepository families, ItemSubFamilyRepository subFamilies, ItemRepository items,
			ItemBarcodeRepository barcodes, ItemCompositionRepository compositions, HoPriceListLineRepository priceLines,
			Supplier<CopiesDownFeed> feed, TransactionOperations writeTransactions,
			HoStockPointItemRepository stockPointItems, StoreRepository stores, ItemCategoryRepository categories,
			ItemSectionRepository sections) {
		this.categories = categories;
		this.sections = sections;
		this.stockPointItems = stockPointItems;
		this.stores = stores;
		this.families = families;
		this.subFamilies = subFamilies;
		this.items = items;
		this.barcodes = barcodes;
		this.compositions = compositions;
		this.priceLines = priceLines;
		this.feed = feed;
		this.writeTransactions = writeTransactions;
	}

	// ─── Copies down ─────────────────────────────────────────────

	@Override
	public DataDomain getDomain() {
		return DataDomain.CATALOGUE;
	}

	@Override
	public Map<String, JsonNode> load(Store store, List<String> codes) {
		Map<CatalogueKind, List<String>> byKind = new EnumMap<>(CatalogueKind.class);
		for (String recordCode : codes) {
			CatalogueKind kind = CatalogueKind.ofRecordCode(recordCode);
			if (kind != null) {
				byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(CatalogueKind.codeOf(recordCode));
			}
		}
		Map<String, JsonNode> copies = new LinkedHashMap<>();
		// Item variants (ZEN), step 7: the catégories and the sections, for every store, as the families
		if (byKind.containsKey(CatalogueKind.CATEGORY) && categories != null) {
			for (ItemCategory category : categories.findByCodeIn(byKind.get(CatalogueKind.CATEGORY))) {
				copies.put(CatalogueKind.CATEGORY.recordCode(category.getCode()),
						COPY_MAPPER.valueToTree(CatalogueCategoryCopyDTO.of(category)));
			}
		}
		if (byKind.containsKey(CatalogueKind.SECTION) && sections != null) {
			for (ItemSection section : sections.findByCodeIn(byKind.get(CatalogueKind.SECTION))) {
				copies.put(CatalogueKind.SECTION.recordCode(section.getCode()),
						COPY_MAPPER.valueToTree(CatalogueSectionCopyDTO.of(section)));
			}
		}
		if (byKind.containsKey(CatalogueKind.FAMILY)) {
			for (ItemFamily family : families.findByCodeIn(byKind.get(CatalogueKind.FAMILY))) {
				copies.put(CatalogueKind.FAMILY.recordCode(family.getCode()),
						COPY_MAPPER.valueToTree(CatalogueFamilyCopyDTO.of(family)));
			}
		}
		if (byKind.containsKey(CatalogueKind.SUBFAMILY)) {
			for (ItemSubFamily subFamily : subFamilies.findByCodeIn(byKind.get(CatalogueKind.SUBFAMILY))) {
				copies.put(CatalogueKind.SUBFAMILY.recordCode(subFamily.getCode()),
						COPY_MAPPER.valueToTree(CatalogueSubFamilyCopyDTO.of(subFamily)));
			}
		}
		if (byKind.containsKey(CatalogueKind.ITEM)) {
			List<Item> found = items.findByItemCodeIn(byKind.get(CatalogueKind.ITEM)).stream()
					.filter(item -> !isTaxStamp(item)).collect(Collectors.toList());
			List<Long> ids = found.stream().map(Item::getId).collect(Collectors.toList());
			Map<Long, List<ItemComposition>> packs = ids.isEmpty() ? new HashMap<>()
					: compositions.findByParentItemIdIn(ids).stream()
							.collect(Collectors.groupingBy(c -> c.getParentItem().getId()));
			Map<Long, Double> listPrices = listPrices(store, ids);
			if (!hasStockPoint(store)) {
				for (Item item : found) {
					copies.put(CatalogueKind.ITEM.recordCode(item.getItemCode()), COPY_MAPPER.valueToTree(
							CatalogueItemCopyDTO.of(item, priceFor(item, listPrices), packs.get(item.getId()))));
				}
			} else {
				Map<Long, HoStockPointItem> rows = pointRows(store, ids);
				for (Item item : found) {
					CatalogueItemCopyDTO copy = pointCopy(item, rows, packs.get(item.getId()));
					if (copy != null) {
						copies.put(CatalogueKind.ITEM.recordCode(item.getItemCode()), COPY_MAPPER.valueToTree(copy));
					}
				}
			}
		}
		if (byKind.containsKey(CatalogueKind.BARCODE)) {
			List<ItemBarcode> found = barcodes.findByBarcodeIn(byKind.get(CatalogueKind.BARCODE));
			if (!hasStockPoint(store)) {
				for (ItemBarcode barcode : found) {
					if (!isTaxStamp(barcode.getItem())) {
						copies.put(CatalogueKind.BARCODE.recordCode(barcode.getBarcode()),
								COPY_MAPPER.valueToTree(CatalogueBarcodeCopyDTO.of(barcode)));
					}
				}
			} else {
				Map<Long, HoStockPointItem> rows = pointRows(store, found.stream().filter(b -> b.getItem() != null)
						.map(b -> b.getItem().getId()).distinct().collect(Collectors.toList()));
				for (ItemBarcode barcode : found) {
					CatalogueBarcodeCopyDTO copy = pointCopy(barcode, rows);
					if (copy != null) {
						copies.put(CatalogueKind.BARCODE.recordCode(barcode.getBarcode()), COPY_MAPPER.valueToTree(copy));
					}
				}
			}
		}
		return copies;
	}

	// ─── Stock points (release 2.2, step 3a) ─────────────────────

	/**
	 * This head office's catalogue comes from the ERP: every store's copies come from the rows of its point. Release 2.2,
	 * after the second test: a store with no point is a point with no rows (it receives no ERP item and no ERP barcode);
	 * the 2.1 path stays for a head office with a manual catalogue only.
	 */
	private boolean hasStockPoint(Store store) {
		return stockPointItems != null;
	}

	/** The rows of the store's point for these items, by item id; none for a store with no point. */
	private Map<Long, HoStockPointItem> pointRows(Store store, List<Long> itemIds) {
		Map<Long, HoStockPointItem> rows = new HashMap<>();
		if (store.getStockPointId() == null) {
			return rows;
		}
		for (int from = 0; from < itemIds.size(); from += IMPORT_CHUNK) {
			for (HoStockPointItem row : stockPointItems.findByStockPointIdAndItemIdIn(store.getStockPointId(),
					itemIds.subList(from, Math.min(from + IMPORT_CHUNK, itemIds.size())))) {
				rows.put(row.getItemId(), row);
			}
		}
		return rows;
	}

	/** Came from the ERP: only those items are filtered by the stock point (a hand-made item goes to every store). */
	static boolean fromErp(Item item) {
		return item != null && fromErp(item.getErpExternalId());
	}

	static boolean fromErp(String erpExternalId) {
		return erpExternalId != null && !erpExternalId.trim().isEmpty();
	}

	/**
	 * The copy of an item for a store with a stock point: null (answered as removed) when the item came from the ERP and
	 * has no row in the point; otherwise built from the item, then the row's name, description, family, sub-family,
	 * price and active flag (active only when the item is too). Price: always the row's (release 2.2: the selling price
	 * lists are not used on this head office), its clearance price when it applies ({@link StockPointPrice}, item
	 * variants (ZEN), 2026-10-17), the copy then carrying clearance true and the row's public price. An item not from
	 * the ERP: the item as it is, at its own price.
	 */
	static CatalogueItemCopyDTO pointCopy(Item item, Map<Long, HoStockPointItem> rows, List<ItemComposition> packs) {
		if (!fromErp(item)) {
			return CatalogueItemCopyDTO.of(item, item.getUnitPrice(), packs);
		}
		HoStockPointItem row = rows.get(item.getId());
		if (row == null) {
			return null;
		}
		CatalogueItemCopyDTO copy = CatalogueItemCopyDTO.of(item, StockPointPrice.sellingPrice(row), packs);
		boolean clearance = StockPointPrice.clearanceApplies(row);
		copy.setClearance(clearance ? Boolean.TRUE : null);
		copy.setPublicPrice(clearance ? row.getUnitPrice() : null);
		copy.setName(row.getName());
		copy.setDescription(row.getDescription());
		copy.setFamilyCode(row.getFamilyCode());
		copy.setSubFamilyCode(row.getSubFamilyCode());
		copy.setActive(!Boolean.FALSE.equals(row.getActive()) && !Boolean.FALSE.equals(item.getActive()));
		return copy;
	}

	/**
	 * The copy of a barcode for a store with a stock point: null for TAX_STAMP, and for a barcode whose item came from
	 * the ERP and has no row in the point (it would wait at the store for ever); active only when the barcode, the item
	 * and the row are active.
	 */
	static CatalogueBarcodeCopyDTO pointCopy(ItemBarcode barcode, Map<Long, HoStockPointItem> rows) {
		Item item = barcode.getItem();
		if (isTaxStamp(item)) {
			return null;
		}
		CatalogueBarcodeCopyDTO copy = CatalogueBarcodeCopyDTO.of(barcode);
		if (!fromErp(item)) {
			return copy;
		}
		HoStockPointItem row = rows.get(item.getId());
		if (row == null) {
			return null;
		}
		copy.setActive(Boolean.TRUE.equals(copy.getActive()) && !Boolean.FALSE.equals(row.getActive()));
		return copy;
	}

	/**
	 * Rows of a point written by the items run (HoStockPointRows, same transaction): each item sent again to the stores of
	 * that point only, and the barcodes of the items that entered the point or changed their active flag. Nothing when
	 * no store has the point. Recorded in bulk.
	 */
	@Transactional
	public void stockPointRowsChanged(long pointId, Collection<Long> itemIds, Collection<Long> barcodeItemIds) {
		if (stores == null || itemIds.isEmpty()) {
			return;
		}
		feed.get().lockSequence(DataDomain.CATALOGUE); // the stores read after the lock (step 4)
		List<Long> storeIds = stores.findIdsByStockPointId(pointId);
		if (storeIds.isEmpty()) {
			return;
		}
		List<String> recordCodes = new ArrayList<>();
		List<Long> ids = new ArrayList<>(itemIds);
		for (int from = 0; from < ids.size(); from += IMPORT_CHUNK) {
			for (Item item : items.findAllById(ids.subList(from, Math.min(from + IMPORT_CHUNK, ids.size())))) {
				addRecordCode(recordCodes, CatalogueKind.ITEM, item);
			}
		}
		List<Long> barcodeItems = new ArrayList<>(barcodeItemIds);
		for (int from = 0; from < barcodeItems.size(); from += IMPORT_CHUNK) {
			for (ItemBarcode barcode : barcodes
					.findByItemIdIn(barcodeItems.subList(from, Math.min(from + IMPORT_CHUNK, barcodeItems.size())))) {
				if (!isTaxStamp(barcode.getItem()) && fits(CatalogueKind.BARCODE, barcode.getBarcode())) {
					recordCodes.add(CatalogueKind.BARCODE.recordCode(barcode.getBarcode()));
				}
			}
		}
		feed.get().recordChanges(DataDomain.CATALOGUE, recordCodes, StoreTargets.of(storeIds));
	}

	/**
	 * A store's stock point changed (given, changed or taken away; StoreService, same transaction), or a store was
	 * created with one: the items with a row in the previous or the new point, active or not, and their barcodes, are
	 * sent again to that store only, recorded in bulk (the copy of any other item does not depend on the point). Returns
	 * how many codes were recorded.
	 */
	@Transactional
	public int storeStockPointChanged(long storeId, Long previousId, Long newId) {
		List<Long> pointIds = new ArrayList<>();
		for (Long pointId : new Long[] { previousId, newId }) {
			if (pointId != null && !pointIds.contains(pointId)) {
				pointIds.add(pointId);
			}
		}
		if (stockPointItems == null || pointIds.isEmpty()) {
			return 0;
		}
		feed.get().lockSequence(DataDomain.CATALOGUE); // the rows read after the lock (step 4)
		List<String> recordCodes = new ArrayList<>();
		for (String code : stockPointItems.findItemCodesOfPoints(pointIds, CatalogueKind.TAX_STAMP_CODE)) {
			if (code != null && !code.trim().isEmpty() && fits(CatalogueKind.ITEM, code)) {
				recordCodes.add(CatalogueKind.ITEM.recordCode(code));
			}
		}
		for (String barcode : stockPointItems.findBarcodesOfPoints(pointIds, CatalogueKind.TAX_STAMP_CODE)) {
			if (barcode != null && !barcode.trim().isEmpty() && fits(CatalogueKind.BARCODE, barcode)) {
				recordCodes.add(CatalogueKind.BARCODE.recordCode(barcode));
			}
		}
		feed.get().recordChangesForStore(DataDomain.CATALOGUE, recordCodes, storeId); // JDBC batch
		return recordCodes.size();
	}

	private static void addRecordCode(List<String> recordCodes, CatalogueKind kind, Item item) {
		if (item != null && !isTaxStamp(item) && item.getItemCode() != null && fits(kind, item.getItemCode())) {
			recordCodes.add(kind.recordCode(item.getItemCode()));
		}
	}

	/**
	 * item.id to its price in the store's list; empty when the store has no list. Release 2.2: always empty on a head
	 * office whose catalogue comes from the ERP (the price is the point row's; an old list on a store is ignored, not
	 * deleted).
	 */
	private Map<Long, Double> listPrices(Store store, List<Long> itemIds) {
		Map<Long, Double> prices = new HashMap<>();
		if (stockPointItems != null || store.getSellingPriceListId() == null || itemIds.isEmpty()) {
			return prices;
		}
		for (HoPriceListLine line : priceLines.findByPriceListIdAndItemIdIn(store.getSellingPriceListId(), itemIds)) {
			prices.put(line.getItemId(), line.getPrice());
		}
		return prices;
	}

	/** The price sent to a store: the line of its list for the item, otherwise the base price. */
	static Double priceFor(Item item, Map<Long, Double> listPrices) {
		Double listed = listPrices.get(item.getId());
		return listed != null ? listed : item.getUnitPrice();
	}

	@Override
	public Map<String, StoreTargets> currentTargets() {
		Map<String, StoreTargets> result = new LinkedHashMap<>();
		if (categories != null) {
			addTargets(result, CatalogueKind.CATEGORY, categories.findAllCodes()); // item variants (ZEN), step 7
		}
		addTargets(result, CatalogueKind.FAMILY, families.findAllCodes());
		addTargets(result, CatalogueKind.SUBFAMILY, subFamilies.findAllCodes());
		if (sections != null) {
			addTargets(result, CatalogueKind.SECTION, sections.findAllCodes());
		}
		if (stockPointItems != null) {
			addPointTargets(result);
			return result;
		}
		addTargets(result, CatalogueKind.ITEM, items.findAllCodes().stream()
				.filter(code -> !CatalogueKind.TAX_STAMP_CODE.equals(code)).collect(Collectors.toList()));
		addTargets(result, CatalogueKind.BARCODE, barcodes.findAllBarcodesExceptItem(CatalogueKind.TAX_STAMP_CODE));
		return result;
	}

	/**
	 * Stock point copies, step 4: the items and barcodes on a head office whose catalogue comes from the ERP. An item
	 * that came from the ERP, and its barcodes, for the stores whose point has a row for it (active or not), none when no
	 * point has it; a hand-made item and its barcodes for every store; TAX_STAMP never.
	 */
	private void addPointTargets(Map<String, StoreTargets> result) {
		Map<Long, Set<Long>> storesByItem = new HashMap<>();
		for (Object[] pair : stockPointItems.findAllStoreAndItemIds()) {
			storesByItem.computeIfAbsent((Long) pair[1], k -> new TreeSet<>()).add((Long) pair[0]);
		}
		Map<Long, StoreTargets> byItem = new HashMap<>();
		for (Object[] key : stockPointItems.findItemKeys()) {
			String code = (String) key[1];
			if (code == null || code.trim().isEmpty() || CatalogueKind.TAX_STAMP_CODE.equals(code)) {
				continue;
			}
			StoreTargets targets = fromErp((String) key[2])
					? StoreTargets.of(storesByItem.getOrDefault((Long) key[0], Collections.emptySet()))
					: StoreTargets.all();
			byItem.put((Long) key[0], targets);
			addTarget(result, CatalogueKind.ITEM, code, targets);
		}
		for (Object[] pair : stockPointItems.findBarcodeItemIds()) {
			StoreTargets targets = byItem.get((Long) pair[1]);
			if (targets != null) {
				addTarget(result, CatalogueKind.BARCODE, (String) pair[0], targets);
			}
		}
	}

	/** A code with its targets; nothing for a blank or too long code or for no store. */
	private static void addTarget(Map<String, StoreTargets> result, CatalogueKind kind, String code,
			StoreTargets targets) {
		if (code != null && !code.trim().isEmpty() && fits(kind, code) && !isNobody(targets)) {
			result.put(kind.recordCode(code), targets);
		}
	}

	private static boolean isNobody(StoreTargets targets) {
		return !targets.isAllStores() && targets.getStoreIds().isEmpty();
	}

	private static void addTargets(Map<String, StoreTargets> result, CatalogueKind kind, List<String> codes) {
		for (String code : codes) {
			if (code != null && !code.trim().isEmpty() && fits(kind, code)) {
				result.put(kind.recordCode(code), StoreTargets.all());
			}
		}
	}

	// ─── Hooks of the catalogue services ─────────────────────────

	@Override
	public void beforeSave(CatalogueKind kind, String previousCode, _BaseEntity record) {
		String code = codeOf(kind, record);
		if (code != null && code.trim().length() > CatalogueKind.MAX_CODE_LENGTH) {
			throw new CatalogueCodeTooLongException("The code '" + code + "' is longer than " + CatalogueKind.MAX_CODE_LENGTH
					+ " characters: the stores could not receive it.");
		}
		if (kind != CatalogueKind.BARCODE && previousCode != null && !previousCode.equals(code)) {
			throw new CatalogueCodeChangeException(codeChangeMessage(kind, previousCode));
		}
	}

	static String codeChangeMessage(CatalogueKind kind, String previousCode) {
		String what = kind == CatalogueKind.ITEM ? "item"
				: kind == CatalogueKind.FAMILY ? "family"
						: kind == CatalogueKind.CATEGORY ? "catégorie" : kind == CatalogueKind.SECTION ? "section" : "sub-family";
		return "The code of the " + what + " " + previousCode
				+ " cannot be changed: the stores know it by its code. Create a new one and deactivate this one.";
	}

	@Override
	@Transactional
	public void afterSave(CatalogueKind kind, String previousCode, _BaseEntity saved) {
		if (isExcluded(kind, saved)) {
			return;
		}
		if (kind == CatalogueKind.ITEM && byStockPoint((Item) saved)) {
			erpItemsSaved(Collections.singletonList((Item) saved));
			return;
		}
		if (kind == CatalogueKind.BARCODE && byStockPoint(((ItemBarcode) saved).getItem())) {
			erpBarcodesSaved(Collections.singletonList((ItemBarcode) saved), previousCode == null
					? Collections.emptyMap()
					: Collections.singletonMap(saved.getId(), new BarcodeBefore(previousCode, null)));
			return;
		}
		String code = codeOf(kind, saved);
		if (kind == CatalogueKind.BARCODE && previousCode != null && !previousCode.equals(code)) {
			record(CatalogueKind.BARCODE, previousCode); // the old value is answered as removed
		}
		record(kind, code);
		if (kind == CatalogueKind.ITEM && saved.getId() != null) {
			recordBarcodesOf(saved.getId()); // a barcode is sent active only while its item is active
		}
	}

	@Override
	@Transactional
	public void beforeDelete(CatalogueKind kind, _BaseEntity record) {
		if (isExcluded(kind, record)) {
			return;
		}
		if (kind == CatalogueKind.ITEM && record.getId() != null) {
			List<HoPriceListLine> lines = priceLines.findByItemId(record.getId());
			if (!lines.isEmpty()) {
				priceLines.deleteAll(lines);
			}
		}
		if (kind == CatalogueKind.ITEM && byStockPoint((Item) record)) {
			erpItemsSaved(Collections.singletonList((Item) record)); // answered as removed once deleted
			return;
		}
		if (kind == CatalogueKind.BARCODE && byStockPoint(((ItemBarcode) record).getItem())) {
			erpBarcodesSaved(Collections.singletonList((ItemBarcode) record), Collections.emptyMap());
			return;
		}
		if (kind == CatalogueKind.ITEM && record.getId() != null) {
			recordBarcodesOf(record.getId());
		}
		record(kind, codeOf(kind, record));
	}

	@Override
	@Transactional
	public void afterPackChanged(Long parentItemId) {
		if (parentItemId == null) {
			return;
		}
		items.findById(parentItemId).filter(item -> !isTaxStamp(item)).ifPresent(item -> {
			if (byStockPoint(item)) {
				Recording recording = new Recording();
				recording.add(CatalogueKind.ITEM, item.getItemCode(),
						targetsOf(item, storesOf(Collections.singletonList(item.getId()))));
				recording.write(feed.get());
			} else {
				record(CatalogueKind.ITEM, item.getItemCode());
			}
		});
	}

	// ─── Stock point copies, step 4: an ERP item for the stores of its points ───

	/**
	 * True on a head office whose catalogue comes from the ERP: the items and barcodes the ERP imports save are recorded
	 * with {@link #erpItemsSaved} and {@link #erpBarcodesSaved} (HeadOfficeErpCatalogueRecorder).
	 */
	public boolean recordsByStockPoint() {
		return stockPointItems != null;
	}

	/** An item that came from the ERP, on a head office with stock points: recorded for the stores of its points only. */
	private boolean byStockPoint(Item item) {
		return stockPointItems != null && fromErp(item);
	}

	/** The stores whose point has a row for each of these items (active or not), by item id; none for an item in no point. */
	private Map<Long, Set<Long>> storesOf(Collection<Long> itemIds) {
		Map<Long, Set<Long>> stores = new HashMap<>();
		List<Long> ids = itemIds.stream().filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
		for (int from = 0; from < ids.size(); from += IMPORT_CHUNK) {
			for (Object[] pair : stockPointItems.findStoreAndItemIds(ids.subList(from, Math.min(from + IMPORT_CHUNK,
					ids.size())))) {
				stores.computeIfAbsent((Long) pair[1], k -> new TreeSet<>()).add((Long) pair[0]);
			}
		}
		return stores;
	}

	/** The targets of a record of this item: the stores of its points for an ERP item, every store otherwise. */
	private StoreTargets targetsOf(Item item, Map<Long, Set<Long>> stores) {
		return byStockPoint(item) ? StoreTargets.of(stores.getOrDefault(item.getId(), Collections.emptySet()))
				: StoreTargets.all();
	}

	/**
	 * Items saved (an ERP import, or one item saved or deleted): an item that came from the ERP and its barcodes for the
	 * stores whose point has a row for it (active or not; none: nothing recorded), grouped by stores, one bulk recording
	 * per group; any other item as {@link #afterSave}. TAX_STAMP never. Inside the caller's transaction.
	 */
	@Transactional
	public void erpItemsSaved(List<? extends Item> saved) {
		List<Item> erp = new ArrayList<>();
		for (Item item : saved) {
			if (item == null || isTaxStamp(item)) {
				continue;
			}
			if (byStockPoint(item) && item.getId() != null) {
				erp.add(item);
			} else {
				afterSave(CatalogueKind.ITEM, null, item);
			}
		}
		if (erp.isEmpty()) {
			return;
		}
		Map<Long, Set<Long>> stores = storesOf(erp.stream().map(Item::getId).collect(Collectors.toList()));
		Map<Long, List<String>> barcodesByItem = barcodesOf(new ArrayList<>(stores.keySet()));
		Recording recording = new Recording();
		for (Item item : erp) {
			StoreTargets targets = targetsOf(item, stores);
			recording.add(CatalogueKind.ITEM, item.getItemCode(), targets);
			for (String barcode : barcodesByItem.getOrDefault(item.getId(), Collections.emptyList())) {
				recording.add(CatalogueKind.BARCODE, barcode, targets);
			}
		}
		recording.write(feed.get());
	}

	/** The barcodes of these items, by item id. */
	private Map<Long, List<String>> barcodesOf(List<Long> itemIds) {
		Map<Long, List<String>> found = new HashMap<>();
		for (int from = 0; from < itemIds.size(); from += IMPORT_CHUNK) {
			for (ItemBarcode barcode : barcodes
					.findByItemIdIn(itemIds.subList(from, Math.min(from + IMPORT_CHUNK, itemIds.size())))) {
				if (barcode.getItem() != null) {
					found.computeIfAbsent(barcode.getItem().getId(), k -> new ArrayList<>()).add(barcode.getBarcode());
				}
			}
		}
		return found;
	}

	/**
	 * Before an ERP import of barcodes: the value and the item of the barcodes it may change, found as the import finds
	 * them (by ERP id, then by value), by barcode id. Empty on a head office without stock points.
	 */
	public Map<Long, BarcodeBefore> barcodesBefore(Collection<String> externalIds, Collection<String> values) {
		Map<Long, BarcodeBefore> before = new HashMap<>();
		if (stockPointItems == null) {
			return before;
		}
		List<String> ids = externalIds.stream().filter(v -> v != null && !v.trim().isEmpty()).distinct()
				.collect(Collectors.toList());
		for (int from = 0; from < ids.size(); from += IMPORT_CHUNK) {
			for (Object[] row : stockPointItems
					.findBarcodesByErpIdIn(ids.subList(from, Math.min(from + IMPORT_CHUNK, ids.size())))) {
				before.put((Long) row[0], new BarcodeBefore((String) row[1], (Long) row[2]));
			}
		}
		List<String> list = values.stream().filter(v -> v != null && !v.trim().isEmpty()).distinct()
				.collect(Collectors.toList());
		for (int from = 0; from < list.size(); from += IMPORT_CHUNK) {
			for (Object[] row : stockPointItems
					.findBarcodesByValueIn(list.subList(from, Math.min(from + IMPORT_CHUNK, list.size())))) {
				before.put((Long) row[0], new BarcodeBefore((String) row[1], (Long) row[2]));
			}
		}
		return before;
	}

	/**
	 * Barcodes saved (an ERP import, or one barcode saved or deleted), with what they were before (by barcode id; may be
	 * empty). A barcode of an ERP item goes to the stores whose point has a row for its item; one that moved from another
	 * item also to the stores of that item (every store when that item was hand-made), so a store that had it gets it as
	 * removed; a value changed under the same ERP id: the old value too, to the same stores. A barcode of any other item
	 * as {@link #afterSave}. TAX_STAMP never. Inside the caller's transaction.
	 */
	@Transactional
	public void erpBarcodesSaved(List<? extends ItemBarcode> saved, Map<Long, BarcodeBefore> before) {
		List<ItemBarcode> erp = new ArrayList<>();
		Set<Long> itemIds = new java.util.LinkedHashSet<>();
		for (ItemBarcode barcode : saved) {
			if (barcode == null || barcode.getItem() == null || isTaxStamp(barcode.getItem())) {
				continue;
			}
			BarcodeBefore was = before.get(barcode.getId());
			if (!byStockPoint(barcode.getItem())) {
				afterSave(CatalogueKind.BARCODE, oldValue(was, barcode), barcode); // every store covers the old item
				continue;
			}
			erp.add(barcode);
			itemIds.add(barcode.getItem().getId());
			Long oldItemId = oldItemId(was, barcode);
			if (oldItemId != null) {
				itemIds.add(oldItemId);
			}
		}
		if (erp.isEmpty()) {
			return;
		}
		Map<Long, Set<Long>> stores = storesOf(itemIds);
		Map<Long, Item> oldItems = new HashMap<>();
		List<Long> oldIds = erp.stream().map(b -> oldItemId(before.get(b.getId()), b)).filter(java.util.Objects::nonNull)
				.distinct().collect(Collectors.toList());
		if (!oldIds.isEmpty()) {
			items.findAllById(oldIds).forEach(item -> oldItems.put(item.getId(), item));
		}
		Recording recording = new Recording();
		for (ItemBarcode barcode : erp) {
			BarcodeBefore was = before.get(barcode.getId());
			StoreTargets targets = targetsOf(barcode.getItem(), stores);
			Long oldItemId = oldItemId(was, barcode);
			if (oldItemId != null) {
				Item oldItem = oldItems.get(oldItemId);
				targets = targets.union(oldItem == null || fromErp(oldItem)
						? StoreTargets.of(stores.getOrDefault(oldItemId, Collections.emptySet()))
						: StoreTargets.all());
			}
			recording.add(CatalogueKind.BARCODE, barcode.getBarcode(), targets);
			recording.add(CatalogueKind.BARCODE, oldValue(was, barcode), targets);
		}
		recording.write(feed.get());
	}

	/** The value before, when it was another one; null otherwise. */
	private static String oldValue(BarcodeBefore was, ItemBarcode barcode) {
		return was != null && was.value != null && !was.value.equals(barcode.getBarcode()) ? was.value : null;
	}

	/** The item before, when it was another one; null otherwise (or unknown). */
	private static Long oldItemId(BarcodeBefore was, ItemBarcode barcode) {
		return was != null && was.itemId != null && !was.itemId.equals(barcode.getItem().getId()) ? was.itemId : null;
	}

	/** A barcode's value and item before an ERP import (item null: unknown). */
	public static final class BarcodeBefore {
		final String value;
		final Long itemId;

		public BarcodeBefore(String value, Long itemId) {
			this.value = value;
			this.itemId = itemId;
		}
	}

	/** Codes to record, each with its stores (merged when a code comes twice), written grouped by stores. */
	private static final class Recording {
		private final Map<String, StoreTargets> targets = new LinkedHashMap<>();

		/** Nothing for a blank or too long code or for no store. */
		void add(CatalogueKind kind, String code, StoreTargets stores) {
			if (code != null && !code.trim().isEmpty() && fits(kind, code) && !isNobody(stores)) {
				targets.merge(kind.recordCode(code), stores, StoreTargets::union);
			}
		}

		/** One bulk recording per group of codes with the same stores (one sequence update each). */
		void write(CopiesDownFeed feed) {
			Map<StoreTargets, List<String>> groups = new LinkedHashMap<>();
			targets.forEach((code, stores) -> groups.computeIfAbsent(stores, k -> new ArrayList<>()).add(code));
			groups.forEach((stores, codes) -> feed.recordChanges(DataDomain.CATALOGUE, codes, stores));
		}
	}

	@Override
	public void afterImport(CatalogueKind kind, Collection<String> codes) {
		List<String> list = codes.stream().filter(code -> code != null && !code.trim().isEmpty())
				.filter(code -> kind != CatalogueKind.ITEM || !CatalogueKind.TAX_STAMP_CODE.equals(code)).distinct()
				.filter(code -> fits(kind, code)).collect(Collectors.toList());
		for (int from = 0; from < list.size(); from += IMPORT_CHUNK) {
			List<String> chunk = list.subList(from, Math.min(from + IMPORT_CHUNK, list.size()));
			writeTransactions.executeWithoutResult(status -> chunk.forEach(code -> record(kind, code)));
		}
		if (!list.isEmpty()) {
			log.info("Head office catalogue: {} {} records from a data import made available to the stores", list.size(),
					kind);
		}
	}

	/** The price lists' changes (task 6.4): an item sent again to these stores. Inside the caller's transaction. */
	void recordItem(String itemCode, StoreTargets stores) {
		if (itemCode != null && !CatalogueKind.TAX_STAMP_CODE.equals(itemCode) && fits(CatalogueKind.ITEM, itemCode)) {
			feed.get().recordChange(DataDomain.CATALOGUE, CatalogueKind.ITEM.recordCode(itemCode), stores);
		}
	}

	private void recordBarcodesOf(Long itemId) {
		for (ItemBarcode barcode : barcodes.findByItemId(itemId)) {
			record(CatalogueKind.BARCODE, barcode.getBarcode());
		}
	}

	private void record(CatalogueKind kind, String code) {
		if (code != null && !code.trim().isEmpty() && fits(kind, code)) {
			feed.get().recordChange(DataDomain.CATALOGUE, kind.recordCode(code), StoreTargets.all());
		}
	}

	/** False (with a WARN line) for a code too long to travel: record_code holds 100 characters. */
	private static boolean fits(CatalogueKind kind, String code) {
		if (code.length() <= CatalogueKind.MAX_CODE_LENGTH) {
			return true;
		}
		log.warn("Head office catalogue: the {} code {} is longer than {} characters and is not sent to the stores", kind,
				code, CatalogueKind.MAX_CODE_LENGTH);
		return false;
	}

	/** The business code of a record of this kind. */
	static String codeOf(CatalogueKind kind, _BaseEntity record) {
		switch (kind) {
			case CATEGORY:
				return ((ItemCategory) record).getCode();
			case SECTION:
				return ((ItemSection) record).getCode();
			case FAMILY:
				return ((ItemFamily) record).getCode();
			case SUBFAMILY:
				return ((ItemSubFamily) record).getCode();
			case ITEM:
				return ((Item) record).getItemCode();
			case BARCODE:
				return ((ItemBarcode) record).getBarcode();
			default:
				throw new IllegalArgumentException(String.valueOf(kind));
		}
	}

	private static boolean isExcluded(CatalogueKind kind, _BaseEntity record) {
		return (kind == CatalogueKind.ITEM && isTaxStamp((Item) record))
				|| (kind == CatalogueKind.BARCODE && isTaxStamp(((ItemBarcode) record).getItem()));
	}

	private static boolean isTaxStamp(Item item) {
		return item != null && CatalogueKind.TAX_STAMP_CODE.equals(item.getItemCode());
	}
}
