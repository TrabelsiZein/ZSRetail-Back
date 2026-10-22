package com.digithink.zsretail.core.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Subquery;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.core.repository.PosItemSearchRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.utils.SearchText;

@Service
public class ItemService extends _BaseService<Item, Long> {

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private ItemFamilyRepository itemFamilyRepository;

	@Autowired
	private ItemSubFamilyRepository itemSubFamilyRepository;


	/** Step 6: a head office that sends its catalogue records each change; no bean on a store. */
	@Autowired(required = false)
	private ObjectProvider<CatalogueHeadOfficeHooks> catalogueHooks;

	@Autowired
	private StockService stockService;

	@Autowired
	private StockMovementService stockMovementService;

	@Autowired
	private ItemBarcodeRepository itemBarcodeRepository;

	@Autowired
	private PosItemSearchRepository posItemSearchRepository;

	@Autowired
	private ItemSectionRepository itemSectionRepository;

	/** 3.1.0 (ZEN): whether the installation has catégories and sections (the till's extra levels). */
	@Autowired
	private ItemTreeService itemTreeService;

	/** 3.1.0, the till's search: the most items one answer holds. */
	public static final int POS_SEARCH_LIMIT = 30;

	/** 3.1.0, the purchase search: the most items found by a part of a barcode (SQL Server takes 2,100 parameters). */
	static final int BARCODE_MATCHES_LIMIT = 1000;

	/**
	 * 3.1.0: the till's search answer, the items, whether there were more than {@link #POS_SEARCH_LIMIT}, and the item
	 * of the whole barcode or code typed (null when none: Enter in the till's search adds that item).
	 */
	public static final class PosSearch {
		private final List<Item> items;
		private final boolean more;
		private final Long exactId;

		PosSearch(List<Item> items, boolean more, Long exactId) {
			this.items = items;
			this.more = more;
			this.exactId = exactId;
		}

		public List<Item> getItems() {
			return items;
		}

		public boolean isMore() {
			return more;
		}

		public Long getExactId() {
			return exactId;
		}
	}

	/**
	 * 3.1.0: the till's search over the whole catalogue, whatever family or sub-family is open. A whole barcode or item
	 * code typed comes first (exact, through its unique index); then the items whose name or code holds every word typed,
	 * anywhere and in any order, case and accents ignored, by name ({@link PosItemSearchRepository}). Only the items the
	 * grid lists ({@link #listedInPos}, in an active sub-family or none). The words: {@link SearchText#words} (fewer than
	 * 2 characters: nothing). The families and sub-families found by the same text: PosCatalogueService.search.
	 */
	public PosSearch searchPos(String query) {
		List<String> words = SearchText.words(query);
		if (words.isEmpty()) {
			return new PosSearch(List.of(), false, null);
		}
		Map<Long, Item> found = new LinkedHashMap<>();
		Optional<Item> exact = exactPosMatch(query.trim());
		exact.ifPresent(item -> found.put(item.getId(), item));
		Long exactId = exact.map(Item::getId).orElse(null);
		List<Long> ids = posItemSearchRepository.findIds(words, POS_SEARCH_LIMIT + 1);
		boolean more = ids.size() > POS_SEARCH_LIMIT;
		Map<Long, Item> byId = itemRepository.findAllById(ids).stream()
				.collect(Collectors.toMap(Item::getId, item -> item));
		for (Long id : ids) {
			Item item = byId.get(id);
			if (item != null) {
				found.putIfAbsent(id, item);
			}
		}
		List<Item> items = new ArrayList<>(found.values());
		if (items.size() > POS_SEARCH_LIMIT) {
			return new PosSearch(new ArrayList<>(items.subList(0, POS_SEARCH_LIMIT)), true, exactId);
		}
		return new PosSearch(items, more, exactId);
	}

	/** The item of a whole barcode (active) or item code typed, when the grid would list it. */
	private Optional<Item> exactPosMatch(String text) {
		Optional<Item> byBarcode = itemBarcodeRepository.findByBarcode(text)
				.filter(barcode -> barcode.getActive() == null || Boolean.TRUE.equals(barcode.getActive()))
				.map(ItemBarcode::getItem);
		return (byBarcode.isPresent() ? byBarcode : itemRepository.findByItemCode(text))
				.filter(ItemService::shownInPosGrid);
	}

	/** {@link #listedInPos} in an active sub-family or none: the items the grid can reach (POS_GRID_COUNTS). */
	static boolean shownInPosGrid(Item item) {
		ItemSubFamily subFamily = item.getItemSubFamily();
		return listedInPos(item)
				&& (subFamily == null || subFamily.getActive() == null || Boolean.TRUE.equals(subFamily.getActive()));
	}

	/**
	 * Manual stock adjustment (POST /item/{id}/adjust-stock): the quantity as before (StockService) and, since step 7A,
	 * its ADJUSTMENT_IN or ADJUSTMENT_OUT movement with the reason as note, in one transaction. Both do nothing when the
	 * supply is the ERP's. No item save: no catalogue change is recorded on a head office. 3.1.0: the delta may carry
	 * decimals (ItemAPI checks them against QuantityPolicy first).
	 */
	@Transactional(rollbackFor = Exception.class)
	public void adjustStock(Long itemId, BigDecimal delta, String reason) {
		stockService.adjustStock(itemId, delta, reason);
		stockMovementService.recordAdjustment(itemId, delta, reason);
	}

	@Override
	protected _BaseRepository<Item, Long> getRepository() {
		return itemRepository;
	}

	/** Which items a list returns by their active flag: ACTIVE (the default of every screen), INACTIVE or ALL. */
	public enum ItemStatusFilter {
		ACTIVE, INACTIVE, ALL
	}

	public Page<Item> findActiveItems(String search, Long familyId, Long subFamilyId, Double priceMin, Double priceMax,
			Boolean withBarcodesOnly, Pageable pageable) {
		return findItems(ItemStatusFilter.ACTIVE, search, familyId, subFamilyId, priceMin, priceMax, withBarcodesOnly,
				pageable);
	}

	/**
	 * The items of the lists with the same filters as {@link #findActiveItems}, by status: ACTIVE (active true, as
	 * before), INACTIVE (active false or empty), ALL (both). Items hidden from the till (showInPos false, e.g. TAX_STAMP)
	 * are never returned, whatever the status.
	 */
	public Page<Item> findItems(ItemStatusFilter status, String search, Long familyId, Long subFamilyId, Double priceMin,
			Double priceMax, Boolean withBarcodesOnly, Pageable pageable) {
		return itemRepository.findAll(itemsSpecification(status, search, familyId, subFamilyId, priceMin, priceMax,
				withBarcodesOnly), pageable);
	}

	static Specification<Item> itemsSpecification(ItemStatusFilter status, String search, Long familyId,
			Long subFamilyId, Double priceMin, Double priceMax, Boolean withBarcodesOnly) {
		ItemStatusFilter wanted = status == null ? ItemStatusFilter.ACTIVE : status;
		return (root, query, cb) -> {
			query.distinct(true);
			Predicate predicate = cb.conjunction();
			if (wanted == ItemStatusFilter.ACTIVE) {
				predicate = cb.and(predicate, cb.isTrue(root.get("active")));
			} else if (wanted == ItemStatusFilter.INACTIVE) {
				predicate = cb.and(predicate, cb.or(cb.isFalse(root.get("active")), cb.isNull(root.get("active"))));
			}

			// Only show items visible in POS (hide system items e.g. Tax Stamp)
			Predicate showInPos = cb.or(cb.isTrue(root.get("showInPos")), cb.isNull(root.get("showInPos")));
			predicate = cb.and(predicate, showInPos);

			// Filter out items with price 0 or null
//			Predicate priceNotNull = cb.isNotNull(root.get("unitPrice"));
//			Predicate priceNotZero = cb.notEqual(root.get("unitPrice"), 0.0);
//			predicate = cb.and(predicate, priceNotNull, priceNotZero);

			if (Boolean.TRUE.equals(withBarcodesOnly)) {
				// Item has barcode in ItemBarcode table OR in legacy Item.barcode field
				Subquery<Long> hasItemBarcode = query.subquery(Long.class);
				var bcRoot = hasItemBarcode.from(com.digithink.zsretail.core.model.ItemBarcode.class);
				hasItemBarcode.select(bcRoot.get("item").get("id"));
				hasItemBarcode.where(cb.equal(bcRoot.get("item").get("id"), root.get("id")),
						cb.or(cb.isTrue(bcRoot.get("active")), cb.isNull(bcRoot.get("active"))));
				Predicate hasLegacyBarcode = cb.and(cb.isNotNull(root.get("barcode")),
						cb.notEqual(root.get("barcode"), ""));
				predicate = cb.and(predicate, cb.or(cb.exists(hasItemBarcode), hasLegacyBarcode));
			}

			if (StringUtils.hasText(search)) {
				String likeValue = "%" + search.toLowerCase() + "%";
				// Search in item code and name
				Predicate codeNameMatch = cb.or(cb.like(cb.lower(root.get("itemCode")), likeValue),
						cb.like(cb.lower(root.get("name")), likeValue));

				// Search in barcodes
				Subquery<Long> barcodeSubquery = query.subquery(Long.class);
				var barcodeRoot = barcodeSubquery.from(com.digithink.zsretail.core.model.ItemBarcode.class);
				barcodeSubquery.select(barcodeRoot.get("item").get("id"));
				barcodeSubquery.where(cb.equal(barcodeRoot.get("item").get("id"), root.get("id")),
						cb.or(cb.isTrue(barcodeRoot.get("active")), cb.isNull(barcodeRoot.get("active"))),
						cb.like(cb.lower(barcodeRoot.get("barcode")), likeValue));
				Predicate barcodeMatch = cb.exists(barcodeSubquery);

				// Match if code/name OR barcode matches
				predicate = cb.and(predicate, cb.or(codeNameMatch, barcodeMatch));
			}

			if (familyId != null) {
				predicate = cb.and(predicate, cb.equal(root.get("itemFamily").get("id"), familyId));
			}

			if (subFamilyId != null) {
				predicate = cb.and(predicate, cb.equal(root.get("itemSubFamily").get("id"), subFamilyId));
			}

			if (priceMin != null) {
				predicate = cb.and(predicate, cb.greaterThanOrEqualTo(root.get("unitPrice"), priceMin));
			}

			if (priceMax != null) {
				predicate = cb.and(predicate, cb.lessThanOrEqualTo(root.get("unitPrice"), priceMax));
			}

			return predicate;
		};
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public Item save(Item item) throws Exception {
		if (item.getItemSubFamily() != null) {
			if (item.getItemSubFamily().getId() == null) {
				throw new IllegalArgumentException("Item sub-family ID is required");
			}
			ItemSubFamily persistedSubFamily = itemSubFamilyRepository.findById(item.getItemSubFamily().getId())
					.orElseThrow(() -> new IllegalArgumentException(
							"Item sub-family not found: " + item.getItemSubFamily().getId()));
			item.setItemSubFamily(persistedSubFamily);
			item.setItemFamily(persistedSubFamily.getItemFamily());
		} else if (item.getItemFamily() != null) {
			if (item.getItemFamily().getId() == null) {
				throw new IllegalArgumentException("Item family ID is required");
			}
			ItemFamily persistedFamily = itemFamilyRepository.findById(item.getItemFamily().getId()).orElseThrow(
					() -> new IllegalArgumentException("Item family not found: " + item.getItemFamily().getId()));
			item.setItemFamily(persistedFamily);
		}

		return saveWithHooks(item);
	}

	/** super.save, with the head office catalogue hooks around it when they exist (step 6). */
	private Item saveWithHooks(Item item) throws Exception {
		return CatalogueHookCalls.save(CatalogueHookCalls.hooks(catalogueHooks), CatalogueKind.ITEM, item,
				id -> itemRepository.findById(id).map(Item::getItemCode), super::save);
	}

	@Override
	@Transactional
	public void deleteById(Long id) {
		CatalogueHeadOfficeHooks hooks = CatalogueHookCalls.hooks(catalogueHooks);
		if (hooks != null) {
			itemRepository.findById(id).ifPresent(item -> hooks.beforeDelete(CatalogueKind.ITEM, item));
		}
		super.deleteById(id);
	}

	/**
	 * Lightweight search for purchase forms. Returns all active items matching the
	 * search term (code, name, or barcode). No POS-specific filters (showInPos,
	 * unitPrice > 0) applied.
	 */
	public Page<Item> searchItemsForPurchase(String search, Pageable pageable) {
		String likeValue = StringUtils.hasText(search) ? "%" + search.toLowerCase() + "%" : null;
		// 3.1.0: the items of a part of a barcode first, in one pass over the barcodes; inside the item query (EXISTS or
		// IN) SQL Server scanned the barcodes once per item: 250 ms to 1 s per keystroke on 3,000 to 8,000 items, 8 to
		// 80 ms in two steps
		List<Long> barcodeItemIds = likeValue == null ? List.of()
				: itemBarcodeRepository.findItemIdsByBarcodeLike(likeValue, PageRequest.of(0, BARCODE_MATCHES_LIMIT));
		Specification<Item> spec = (root, query, cb) -> {
			query.distinct(true);
			Predicate predicate = cb.isTrue(root.get("active"));

			if (likeValue != null) {
				Predicate match = cb.or(cb.like(cb.lower(root.get("itemCode")), likeValue),
						cb.like(cb.lower(root.get("name")), likeValue));
				if (!barcodeItemIds.isEmpty()) {
					match = cb.or(match, root.get("id").in(barcodeItemIds));
				}
				predicate = cb.and(predicate, match);
			}

			return predicate;
		};
		return itemRepository.findAll(spec, pageable);
	}

	public List<Item> findActiveByFamilyId(Long familyId) {
		ItemFamily family = itemFamilyRepository.findById(familyId)
				.orElseThrow(() -> new IllegalArgumentException("Item family not found: " + familyId));

		return itemRepository.findByItemFamily(family).stream().filter(ItemService::listedInPos)
				.collect(Collectors.toList());
	}

	/**
	 * The POS grid's rule, the one place (2.2.2): an item the grid lists is active (or not set), shown in the POS (or not
	 * set) and has a unit price above 0. {@code ItemRepository.countPosGridItems} counts with the same conditions in
	 * JPQL (PosGridCountQueryTest keeps both the same).
	 */
	public static boolean listedInPos(Item item) {
		return (item.getActive() == null || Boolean.TRUE.equals(item.getActive()))
				&& (item.getShowInPos() == null || Boolean.TRUE.equals(item.getShowInPos()))
				&& item.getUnitPrice() != null && item.getUnitPrice() > 0;
	}

	public List<Item> findActiveBySubFamilyId(Long subFamilyId) {
		ItemSubFamily subFamily = itemSubFamilyRepository.findById(subFamilyId)
				.orElseThrow(() -> new IllegalArgumentException("Item sub-family not found: " + subFamilyId));

		return itemRepository.findByItemSubFamily(subFamily).stream().filter(ItemService::listedInPos)
				.collect(Collectors.toList());
	}

	/**
	 * 2.2.2: the items of a family that have no sub-family, as the grid lists them (the till shows them under one extra
	 * tile, or directly when the family has no sub-family tile). Until 2.2.1 they could not be reached from the grid.
	 */
	public List<Item> findPosItemsWithoutSubFamily(Long familyId) {
		ItemFamily family = itemFamilyRepository.findById(familyId)
				.orElseThrow(() -> new IllegalArgumentException("Item family not found: " + familyId));
		return itemRepository.findByItemFamilyAndItemSubFamilyIsNull(family).stream().filter(ItemService::listedInPos)
				.collect(Collectors.toList());
	}

	/**
	 * 3.1.0 (ZEN), GET /item/pos/by-section/{id}: the items of a section as the grid lists them: the items of the
	 * section's own sub-family (item_section.sub_family_code) that carry its code. None when the installation has no
	 * section, or the section's sub-family does not exist; unknown section: IllegalArgumentException.
	 */
	public List<Item> findPosItemsOfSection(Long sectionId) {
		if (!itemTreeService.hasSections()) {
			return new ArrayList<>();
		}
		ItemSection section = itemSectionRepository.findById(sectionId)
				.orElseThrow(() -> new IllegalArgumentException("Item section not found: " + sectionId));
		String subFamilyCode = PosCatalogueService.levelCode(section.getSubFamilyCode());
		String code = PosCatalogueService.levelCode(section.getCode());
		Optional<ItemSubFamily> subFamily = subFamilyCode == null ? Optional.empty()
				: itemSubFamilyRepository.findByCode(subFamilyCode);
		if (!subFamily.isPresent() || code == null) {
			return new ArrayList<>();
		}
		return itemRepository.findByItemSubFamily(subFamily.get()).stream().filter(ItemService::listedInPos)
				.filter(item -> code.equals(PosCatalogueService.levelCode(item.getSectionCode())))
				.collect(Collectors.toList());
	}

	/**
	 * 3.1.0 (ZEN), GET /item/pos/without-section/{subFamilyId}: the items of a sub-family that no section of it holds
	 * (no section code, or a code that is not one of its sections), as the grid lists them. The items of an inactive
	 * section of it stay hidden. None when the installation has no section; unknown sub-family: IllegalArgumentException.
	 */
	public List<Item> findPosItemsWithoutSection(Long subFamilyId) {
		if (!itemTreeService.hasSections()) {
			return new ArrayList<>();
		}
		ItemSubFamily subFamily = itemSubFamilyRepository.findById(subFamilyId)
				.orElseThrow(() -> new IllegalArgumentException("Item sub-family not found: " + subFamilyId));
		Set<String> sectionCodes = itemSectionRepository.findBySubFamilyCode(subFamily.getCode()).stream()
				.map(section -> PosCatalogueService.levelCode(section.getCode())).filter(Objects::nonNull)
				.collect(Collectors.toSet());
		return itemRepository.findByItemSubFamily(subFamily).stream().filter(ItemService::listedInPos).filter(item -> {
			String code = PosCatalogueService.levelCode(item.getSectionCode());
			return code == null || !sectionCodes.contains(code);
		}).collect(Collectors.toList());
	}

	/**
	 * 3.1.0 (ZEN), GET /item/pos/without-family?category: the items without famille and sous-famille that carry this
	 * catégorie code, as the grid lists them. None for a blank code or when the installation has no catégorie.
	 */
	public List<Item> findPosItemsWithoutFamily(String categoryCode) {
		String code = PosCatalogueService.levelCode(categoryCode);
		if (code == null || !itemTreeService.hasCategories()) {
			return new ArrayList<>();
		}
		return itemRepository.findByCategoryCodeAndItemFamilyIsNullAndItemSubFamilyIsNull(code).stream()
				.filter(ItemService::listedInPos).collect(Collectors.toList());
	}

	/**
	 * Without an ERP only: ensure a default family and subfamily exist, then
	 * create an item. Caller is responsible for creating ItemBarcode. Returns the
	 * created item.
	 */
	@Transactional(rollbackFor = Exception.class)
	public Item createQuickProduct(String name, String itemCode, Double unitPrice) throws Exception {
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("Product name is required");
		}
		if (unitPrice == null || unitPrice < 0) {
			throw new IllegalArgumentException("Unit price is required and must be >= 0");
		}
		ItemFamily family = itemFamilyRepository.findByCode("STANDALONE_DEFAULT").orElseGet(() -> {
			ItemFamily f = new ItemFamily();
			f.setCode("STANDALONE_DEFAULT");
			f.setName("Default");
			f.setDisplayOrder(0);
			f.setActive(true);
			return itemFamilyRepository.save(f);
		});
		ItemSubFamily subFamily = itemSubFamilyRepository.findByCode("STANDALONE_DEFAULT").orElseGet(() -> {
			ItemSubFamily sf = new ItemSubFamily();
			sf.setCode("STANDALONE_DEFAULT");
			sf.setName("Default");
			sf.setItemFamily(family);
			sf.setDisplayOrder(0);
			sf.setActive(true);
			return itemSubFamilyRepository.save(sf);
		});
		String code = StringUtils.hasText(itemCode) ? itemCode.trim() : generateQuickProductItemCode();
		if (itemRepository.findByItemCode(code).isPresent()) {
			throw new IllegalArgumentException("Item code already exists: " + code);
		}
		Item item = new Item();
		item.setItemCode(code);
		item.setName(name.trim());
		item.setUnitPrice(unitPrice);
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setType(ItemType.PRODUCT);
		item.setShowInPos(true);
		item.setActive(true);
		CatalogueHeadOfficeHooks hooks = CatalogueHookCalls.hooks(catalogueHooks);
		if (hooks != null) { // step 6: the default family and sub-family may have just been created
			hooks.afterSave(CatalogueKind.FAMILY, family.getCode(), family);
			hooks.afterSave(CatalogueKind.SUBFAMILY, subFamily.getCode(), subFamily);
		}
		return saveWithHooks(item);
	}

	private String generateQuickProductItemCode() {
		String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
		long count = itemRepository.count();
		return "ITEM-" + dateStr + "-" + String.format("%03d", (count % 1000) + 1);
	}
}
