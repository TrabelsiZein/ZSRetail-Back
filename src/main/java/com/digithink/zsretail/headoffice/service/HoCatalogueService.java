package com.digithink.zsretail.headoffice.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeWithoutErp;
import com.digithink.zsretail.headoffice.dto.CatalogueBarcodeCopyDTO;
import com.digithink.zsretail.headoffice.dto.CatalogueFamilyCopyDTO;
import com.digithink.zsretail.headoffice.dto.CatalogueItemCopyDTO;
import com.digithink.zsretail.headoffice.dto.CatalogueSubFamilyCopyDTO;
import com.digithink.zsretail.headoffice.model.HoPriceListLine;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoPriceListLineRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemComposition;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemCompositionRepository;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.service.CatalogueCodeChangeException;
import com.digithink.zsretail.service.CatalogueCodeTooLongException;
import com.digithink.zsretail.service.CatalogueHeadOfficeHooks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 6.1: the catalogue of a head office without an ERP on the copies down mechanism (domain
 * CATALOGUE). Families, sub-families, items and barcodes, every record for every store, record codes with a prefix
 * ({@link CatalogueKind}). The item copy carries the price worked out for the store that pulls (task 6.4): the line of
 * its selling price list for the item, otherwise the base price (item.unitPrice). Every save, delete, pack change and
 * data import of a catalogue record is one change for every store. The system item TAX_STAMP never travels. See
 * docs/modules/head-office.md, "Catalogue owned by the head office".
 */
@Service
@ConditionalOnHeadOfficeWithoutErp
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

	@Autowired
	public HoCatalogueService(ItemFamilyRepository families, ItemSubFamilyRepository subFamilies, ItemRepository items,
			ItemBarcodeRepository barcodes, ItemCompositionRepository compositions, HoPriceListLineRepository priceLines,
			ObjectProvider<CopiesDownFeed> feed, PlatformTransactionManager transactionManager) {
		this(families, subFamilies, items, barcodes, compositions, priceLines, (Supplier<CopiesDownFeed>) feed::getObject,
				new TransactionTemplate(transactionManager));
	}

	/** With given collaborators: used by the tests. */
	public HoCatalogueService(ItemFamilyRepository families, ItemSubFamilyRepository subFamilies, ItemRepository items,
			ItemBarcodeRepository barcodes, ItemCompositionRepository compositions, HoPriceListLineRepository priceLines,
			Supplier<CopiesDownFeed> feed, TransactionOperations writeTransactions) {
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
			for (Item item : found) {
				copies.put(CatalogueKind.ITEM.recordCode(item.getItemCode()), COPY_MAPPER.valueToTree(
						CatalogueItemCopyDTO.of(item, priceFor(item, listPrices), packs.get(item.getId()))));
			}
		}
		if (byKind.containsKey(CatalogueKind.BARCODE)) {
			for (ItemBarcode barcode : barcodes.findByBarcodeIn(byKind.get(CatalogueKind.BARCODE))) {
				if (!isTaxStamp(barcode.getItem())) {
					copies.put(CatalogueKind.BARCODE.recordCode(barcode.getBarcode()),
							COPY_MAPPER.valueToTree(CatalogueBarcodeCopyDTO.of(barcode)));
				}
			}
		}
		return copies;
	}

	/** item.id to its price in the store's list; empty when the store has no list. */
	private Map<Long, Double> listPrices(Store store, List<Long> itemIds) {
		Map<Long, Double> prices = new HashMap<>();
		if (store.getSellingPriceListId() == null || itemIds.isEmpty()) {
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
		addTargets(result, CatalogueKind.FAMILY, families.findAllCodes());
		addTargets(result, CatalogueKind.SUBFAMILY, subFamilies.findAllCodes());
		addTargets(result, CatalogueKind.ITEM, items.findAllCodes().stream()
				.filter(code -> !CatalogueKind.TAX_STAMP_CODE.equals(code)).collect(Collectors.toList()));
		addTargets(result, CatalogueKind.BARCODE, barcodes.findAllBarcodesExceptItem(CatalogueKind.TAX_STAMP_CODE));
		return result;
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
		String what = kind == CatalogueKind.ITEM ? "item" : kind == CatalogueKind.FAMILY ? "family" : "sub-family";
		return "The code of the " + what + " " + previousCode
				+ " cannot be changed: the stores know it by its code. Create a new one and deactivate this one.";
	}

	@Override
	@Transactional
	public void afterSave(CatalogueKind kind, String previousCode, _BaseEntity saved) {
		if (isExcluded(kind, saved)) {
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
		items.findById(parentItemId).filter(item -> !isTaxStamp(item))
				.ifPresent(item -> record(CatalogueKind.ITEM, item.getItemCode()));
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
