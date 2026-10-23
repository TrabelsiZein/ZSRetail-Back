package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.NavItemVariantsDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.service.NavItemService;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.headoffice.dto.ItemCatalogueCardDTO;
import com.digithink.zsretail.headoffice.dto.ItemCatalogueFiltersDTO;
import com.digithink.zsretail.headoffice.dto.ItemCatalogueLineDTO;
import com.digithink.zsretail.headoffice.repository.HoItemCatalogueQuery;
import com.digithink.zsretail.headoffice.repository.HoItemCatalogueRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;
import com.digithink.zsretail.headoffice.repository.HoStoreStockRepository;

import lombok.RequiredArgsConstructor;

/**
 * Fiches items (3.1.0), step 1: the list of the head office page "Fiches items" (docs/modules/head-office.md), on every
 * head office: one line per NAV item number, else one per item, grouped and paged by the database
 * ({@link HoItemCatalogueQuery}). The stock is given only where the head office keeps its own; the point de stock
 * counts read ho_stock_point_item (0 on a head office without points de stock).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HoItemCatalogueService {

	/** Lines per page at most. */
	static final int MAX_SIZE = 200;

	private final HoItemCatalogueRepository lines;
	private final HoStockPointItemRepository rows;
	private final HoStockPointRepository points;
	private final ItemCategoryRepository categoryRepository;
	private final ItemSectionRepository sectionRepository;
	private final ApplicationModeService mode;
	private final ItemRepository itemRepository;
	private final ItemBarcodeRepository barcodeRepository;
	private final HoNavItemStockPoints pointPrices;
	private final HoItemCatalogueQuery query;
	private final HoStoreStockRepository storeStocks;

	/** The page's flags and filter choices, from the data (read once when the page opens). */
	public ItemCatalogueFiltersDTO filters() {
		ItemCatalogueFiltersDTO filters = new ItemCatalogueFiltersDTO();
		Object[] flags = query.rows(HoItemCatalogueQuery.FLAGS).get(0);
		filters.setHasVariants(flag(flags[0]));
		filters.setHasFamilies(flag(flags[1]));
		filters.setHasDivision(flag(flags[2]));
		filters.setHasCategory(flag(flags[3]));
		filters.setHasSection(flag(flags[4]));
		filters.setStockPointCount(flags[5] == null ? 0 : ((Number) flags[5]).longValue());
		filters.setHasBarcodes(flag(flags[6]));
		if (filters.isHasFamilies()) {
			for (Object[] row : query.rows(HoItemCatalogueQuery.FAMILIES)) {
				filters.getFamilies().add(
						new ItemCatalogueFiltersDTO.Option(id(row[0]), (String) row[1], (String) row[2], null, (String) row[3]));
			}
			for (Object[] row : query.rows(HoItemCatalogueQuery.SUB_FAMILIES)) {
				filters.getSubFamilies()
						.add(new ItemCatalogueFiltersDTO.Option(id(row[0]), (String) row[1], (String) row[2], id(row[3]), null));
			}
		}
		if (filters.isHasDivision()) {
			for (Object[] row : query.rows(HoItemCatalogueQuery.DIVISIONS)) {
				filters.getDivisions().add(new ItemCatalogueFiltersDTO.Option(null, (String) row[0], null, null, null));
			}
		}
		if (filters.isHasCategory()) {
			for (Object[] row : query.rows(HoItemCatalogueQuery.CATEGORIES)) {
				filters.getCategories()
						.add(new ItemCatalogueFiltersDTO.Option(null, (String) row[0], (String) row[1], null, null));
			}
		}
		if (filters.isHasSection()) {
			for (Object[] row : query.rows(HoItemCatalogueQuery.SECTIONS)) {
				filters.getSections()
						.add(new ItemCatalogueFiltersDTO.Option(null, (String) row[0], (String) row[1], null, (String) row[2]));
			}
		}
		if (filters.getStockPointCount() > 0) {
			for (Object[] row : query.rows(HoItemCatalogueQuery.STOCK_POINTS)) {
				filters.getStockPoints()
						.add(new ItemCatalogueFiltersDTO.Option(id(row[0]), (String) row[1], (String) row[2], null, null));
			}
		}
		return filters;
	}

	private static boolean flag(Object value) {
		return value != null && ((Number) value).intValue() == 1;
	}

	private static Long id(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	/** True when the lines carry the stock (the head office keeps its own). */
	public boolean withStock() {
		return mode.keepsOwnStock();
	}

	/** True when a point de stock exists (the page's point de stock column and tab). */
	public boolean withStockPoints() {
		return points.count() > 0;
	}

	/**
	 * One page of lines. search: NAV item number, item code, name or barcode, contains, any case (blank: none);
	 * activeOnly: the active variants only (a line whose variants are all inactive is absent), else every variant.
	 */
	public Page<ItemCatalogueLineDTO> page(String search, boolean activeOnly, int page, int size) {
		HoItemCatalogueQuery.Criteria criteria = new HoItemCatalogueQuery.Criteria();
		criteria.setLike(NavItemService.likeOf(search));
		criteria.setActiveOnly(activeOnly);
		return page(criteria, page, size);
	}

	/** One page of lines of the criteria (the search already as a like, see NavItemService.likeOf). */
	public Page<ItemCatalogueLineDTO> page(HoItemCatalogueQuery.Criteria criteria, int page, int size) {
		PageRequest paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
		// Only the filters set are in the statement (the barcode search only in a search's, 2fe0b34)
		Page<Object[]> found = query.lines(criteria, paging);
		boolean withStock = withStock();
		List<ItemCatalogueLineDTO> content = found.getContent().stream().map(row -> line(row, withStock))
				.collect(Collectors.toList());
		names(content);
		pointCounts(content);
		return new PageImpl<>(content, found.getPageable(), found.getTotalElements());
	}

	/**
	 * Step 2: the card of one line, every variant active or not. Exactly one key: navItemNo (blank counts as absent), or
	 * itemId (an item with a NAV number gives its NAV item's card); else IllegalArgumentException. Empty when nothing is
	 * found. search: the variant whose item code, legacy barcode or an active barcode equals it (trimmed, any case) is
	 * highlighted, the first one in the order; a "contains" match highlights nothing.
	 */
	public Optional<ItemCatalogueCardDTO> card(String navItemNo, Long itemId, String search) {
		boolean byNavItemNo = navItemNo != null && !navItemNo.trim().isEmpty();
		if (byNavItemNo == (itemId != null)) {
			throw new IllegalArgumentException("Give exactly one of navItemNo and itemId.");
		}
		List<Item> items;
		if (byNavItemNo) {
			items = itemRepository.findNavItemVariants(navItemNo.trim(), null);
		} else {
			Optional<Item> item = itemRepository.findById(itemId);
			if (!item.isPresent()) {
				return Optional.empty();
			}
			items = item.get().getNavItemNo() == null ? Collections.singletonList(item.get())
					: itemRepository.findNavItemVariants(item.get().getNavItemNo(), null);
		}
		if (items.isEmpty()) {
			return Optional.empty();
		}
		ItemCatalogueCardDTO card = new ItemCatalogueCardDTO();
		boolean withStock = withStock();
		boolean withStockPoints = withStockPoints();
		header(card.getHeader(), items, withStock, withStockPoints);
		List<Long> ids = items.stream().map(Item::getId).collect(Collectors.toList());
		Map<Long, List<ItemBarcode>> barcodes = barcodeRepository.findByItemIdInAndActiveTrue(ids).stream()
				.collect(Collectors.groupingBy(barcode -> barcode.getItem().getId()));
		String exact = search == null || search.trim().isEmpty() ? null : search.trim();
		Item highlighted = null;
		for (Item item : items) {
			ItemCatalogueCardDTO.Variant variant = variant(item, withStock,
					barcodes.getOrDefault(item.getId(), Collections.emptyList()));
			if (highlighted == null && exact != null && matches(variant, item, exact)) {
				variant.setHighlighted(true);
				highlighted = item;
			}
			card.getVariants().add(variant);
		}
		if (withStockPoints) {
			Map<Long, Map<Long, NavItemVariantsDTO.PointPrice>> prices = pointPrices.prices(ids);
			Set<Long> pointIds = new LinkedHashSet<>();
			for (ItemCatalogueCardDTO.Variant variant : card.getVariants()) {
				Map<Long, NavItemVariantsDTO.PointPrice> rows = prices.getOrDefault(variant.getItemId(),
						Collections.emptyMap());
				variant.getPrices().putAll(rows);
				pointIds.addAll(rows.keySet());
			}
			if (!pointIds.isEmpty()) {
				card.setStockPoints(pointPrices.points(pointIds));
			}
		}
		card.setDetails(details(highlighted != null ? highlighted : items.get(0), withStock));
		storeStocks(card);
		return Optional.of(card);
	}

	/**
	 * The Stock per store tab: what each active store last sent for the card's item codes (one query), with the
	 * variant's description on a NAV item's rows; withStoreStocks when a row exists.
	 */
	private void storeStocks(ItemCatalogueCardDTO card) {
		Map<String, String> descriptions = new HashMap<>();
		for (ItemCatalogueCardDTO.Variant variant : card.getVariants()) {
			descriptions.put(variant.getItemCode(), variant.getVariantDescription());
		}
		boolean single = card.getHeader().isSingle();
		for (Object[] row : storeStocks.findAtActiveStores(descriptions.keySet())) {
			ItemCatalogueCardDTO.StoreStock stock = new ItemCatalogueCardDTO.StoreStock();
			stock.setStoreCode((String) row[0]);
			stock.setStoreName((String) row[1]);
			stock.setItemCode((String) row[2]);
			stock.setVariantDescription(single ? null : descriptions.get(row[2]));
			stock.setQuantity(row[3] == null ? BigDecimal.ZERO : Quantities.normalize((BigDecimal) row[3]));
			stock.setStoreTime(row[4] == null ? null : row[4].toString());
			card.getStoreStocks().add(stock);
		}
		card.getHeader().setWithStoreStocks(!card.getStoreStocks().isEmpty());
	}

	/** The line's header, with the list's rules: smallest name, family, codes; active when one variant is. */
	private void header(ItemCatalogueCardDTO.Header header, List<Item> items, boolean withStock,
			boolean withStockPoints) {
		Item first = items.get(0);
		String navItemNo = first.getNavItemNo();
		header.setNavItemNo(navItemNo);
		header.setSingle(navItemNo == null);
		if (navItemNo == null) {
			header.setItemId(first.getId());
			header.setCode(first.getItemCode());
		} else {
			header.setCode(navItemNo);
		}
		header.setName(smallest(items, Item::getName));
		header.setFamilyName(smallest(items, item -> item.getItemFamily() == null ? null : item.getItemFamily().getName()));
		header.setSubFamilyName(
				smallest(items, item -> item.getItemSubFamily() == null ? null : item.getItemSubFamily().getName()));
		header.setCategoryCode(smallest(items, Item::getCategoryCode));
		header.setSectionCode(smallest(items, Item::getSectionCode));
		header.setActive(items.stream().anyMatch(HoItemCatalogueService::isActive));
		header.setVariantsCount(items.size());
		header.setWithStock(withStock);
		header.setWithStockPoints(withStockPoints);
		if (header.getCategoryCode() != null) {
			categoryRepository.findByCodeIn(Collections.singleton(header.getCategoryCode())).stream().findFirst()
					.ifPresent(category -> header.setCategoryName(category.getName()));
		}
		if (header.getSectionCode() != null) {
			sectionRepository.findByCodeIn(Collections.singleton(header.getSectionCode())).stream().findFirst()
					.ifPresent(section -> header.setSectionName(section.getName()));
		}
	}

	private static ItemCatalogueCardDTO.Variant variant(Item item, boolean withStock, List<ItemBarcode> barcodes) {
		ItemCatalogueCardDTO.Variant variant = new ItemCatalogueCardDTO.Variant();
		variant.setItemId(item.getId());
		variant.setItemCode(item.getItemCode());
		variant.setVariantCode(item.getVariantCode());
		variant.setVariantDescription(item.getVariantDescription());
		variant.setSizeCode(item.getSizeCode());
		variant.setSizeDescription(item.getSizeDescription());
		variant.setColorCode(item.getColorCode());
		variant.setColorDescription(item.getColorDescription());
		variant.setChoiceCode(item.getChoiceCode());
		variant.setChoiceDescription(item.getChoiceDescription());
		variant.setSeasonCode(item.getSeasonCode());
		variant.setDivisionCode(item.getDivisionCode());
		variant.setActive(isActive(item));
		variant.setType(item.getType() == null ? null : item.getType().name());
		variant.setIsPackage(item.getType() == ItemType.PACKAGE);
		variant.setDefaultVAT(item.getDefaultVAT());
		variant.setUnitPrice(item.getUnitPrice());
		if (withStock) {
			variant.setStockQuantity(item.getStockQuantity() == null ? BigDecimal.ZERO : item.getStockQuantity());
		}
		variant.setMinStockLevel(item.getMinStockLevel());
		for (ItemBarcode barcode : barcodes) {
			variant.getBarcodes().add(new ItemCatalogueCardDTO.Barcode(barcode.getBarcode(),
					Boolean.TRUE.equals(barcode.getIsPrimary()), barcode.getDescription()));
		}
		variant.getBarcodes().sort(Comparator.comparing(ItemCatalogueCardDTO.Barcode::getBarcode,
				Comparator.nullsLast(Comparator.naturalOrder())));
		return variant;
	}

	/** The item code, the legacy barcode or an active barcode equals the search (any case). */
	private static boolean matches(ItemCatalogueCardDTO.Variant variant, Item item, String exact) {
		return exact.equalsIgnoreCase(item.getItemCode()) || exact.equalsIgnoreCase(item.getBarcode())
				|| variant.getBarcodes().stream().anyMatch(barcode -> exact.equalsIgnoreCase(barcode.getBarcode()));
	}

	/** The details of one variant; the last direct cost only where the head office keeps its own stock. */
	private static ItemCatalogueCardDTO.Details details(Item item, boolean withStock) {
		ItemCatalogueCardDTO.Details details = new ItemCatalogueCardDTO.Details();
		details.setItemId(item.getId());
		details.setDescription(item.getDescription());
		details.setUnitOfMeasure(item.getUnitOfMeasure());
		details.setCategory(item.getCategory());
		details.setBrand(item.getBrand());
		details.setItemDiscGroup(item.getItemDiscGroup());
		details.setMaximumAuthorizedDiscount(item.getMaximumAuthorizedDiscount());
		details.setCostPrice(item.getCostPrice());
		details.setMinStockLevel(item.getMinStockLevel());
		if (withStock) {
			details.setLastDirectCost(item.getLastDirectCost());
		}
		details.setShowInPos(!Boolean.FALSE.equals(item.getShowInPos()));
		details.setImageUrl(item.getImageUrl());
		details.setOrigin(item.getOrigin() == null ? null : item.getOrigin().name());
		return details;
	}

	/** A null active counts as active, as everywhere. */
	private static boolean isActive(Item item) {
		return item.getActive() == null || item.getActive();
	}

	/** The smallest non-null value (the list's MIN), null when none. */
	private static String smallest(List<Item> items, Function<Item, String> value) {
		return items.stream().map(value).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
	}

	/**
	 * One row of {@link HoItemCatalogueQuery#SELECT} as a line, read by the column constants (the order
	 * of its aliases, {@link HoItemCatalogueQuery#LINE_COLUMNS}); public for the query test.
	 */
	public static ItemCatalogueLineDTO line(Object[] row, boolean withStock) {
		ItemCatalogueLineDTO line = new ItemCatalogueLineDTO();
		String navItemNo = (String) row[HoItemCatalogueQuery.COL_NAV_ITEM_NO];
		line.setNavItemNo(navItemNo);
		line.setSingle(navItemNo == null);
		if (navItemNo == null) {
			line.setItemId(((Number) row[HoItemCatalogueQuery.COL_ITEM_ID]).longValue());
			line.setCode((String) row[HoItemCatalogueQuery.COL_ITEM_CODE]);
		} else {
			line.setCode(navItemNo);
		}
		line.setName((String) row[HoItemCatalogueQuery.COL_ITEM_NAME]);
		line.setFamilyName((String) row[HoItemCatalogueQuery.COL_FAMILY_NAME]);
		line.setSubFamilyName((String) row[HoItemCatalogueQuery.COL_SUB_FAMILY_NAME]);
		line.setCategoryCode((String) row[HoItemCatalogueQuery.COL_CATEGORY_CODE]);
		line.setSectionCode((String) row[HoItemCatalogueQuery.COL_SECTION_CODE]);
		Object variants = row[HoItemCatalogueQuery.COL_VARIANT_COUNT];
		line.setVariants(variants == null ? 0 : ((Number) variants).longValue());
		Object anyActive = row[HoItemCatalogueQuery.COL_ANY_ACTIVE];
		line.setActive(anyActive != null && ((Number) anyActive).intValue() == 1);
		if (withStock) {
			Object stock = row[HoItemCatalogueQuery.COL_STOCK_QUANTITY];
			line.setStockQuantity(stock == null ? BigDecimal.ZERO : new BigDecimal(stock.toString()));
		}
		line.setPriceMin(price(row[HoItemCatalogueQuery.COL_PRICE_MIN]));
		line.setPriceMax(price(row[HoItemCatalogueQuery.COL_PRICE_MAX]));
		return line;
	}

	/** A price with VAT, to the third decimal (the SQL product of a float and a decimal); null stays null. */
	private static Double price(Object value) {
		return value == null ? null
				: new BigDecimal(value.toString()).setScale(3, java.math.RoundingMode.HALF_UP).doubleValue();
	}

	/** The catégorie and section names of the page's codes, one read each. */
	private void names(List<ItemCatalogueLineDTO> content) {
		Set<String> categoryCodes = content.stream().map(ItemCatalogueLineDTO::getCategoryCode)
				.filter(Objects::nonNull).collect(Collectors.toSet());
		Set<String> sectionCodes = content.stream().map(ItemCatalogueLineDTO::getSectionCode)
				.filter(Objects::nonNull).collect(Collectors.toSet());
		Map<String, String> categories = new HashMap<>();
		if (!categoryCodes.isEmpty()) {
			for (ItemCategory category : categoryRepository.findByCodeIn(categoryCodes)) {
				categories.put(category.getCode(), category.getName());
			}
		}
		Map<String, String> sections = new HashMap<>();
		if (!sectionCodes.isEmpty()) {
			for (ItemSection section : sectionRepository.findByCodeIn(sectionCodes)) {
				sections.put(section.getCode(), section.getName());
			}
		}
		for (ItemCatalogueLineDTO line : content) {
			line.setCategoryName(categories.get(line.getCategoryCode()));
			line.setSectionName(sections.get(line.getSectionCode()));
		}
	}

	/** The active point counts of the page: by NAV item number for the NAV lines, by item id for the single items. */
	private void pointCounts(List<ItemCatalogueLineDTO> content) {
		List<String> navItemNos = content.stream().map(ItemCatalogueLineDTO::getNavItemNo).filter(Objects::nonNull)
				.collect(Collectors.toList());
		List<Long> itemIds = content.stream().map(ItemCatalogueLineDTO::getItemId).filter(Objects::nonNull)
				.collect(Collectors.toList());
		Map<String, Long> byNavItemNo = new HashMap<>();
		if (!navItemNos.isEmpty()) {
			for (Object[] row : rows.countActivePointsOfNavItems(navItemNos)) {
				byNavItemNo.put((String) row[0], ((Number) row[1]).longValue());
			}
		}
		Map<Long, Long> byItemId = new HashMap<>();
		if (!itemIds.isEmpty()) {
			for (Object[] row : lines.countActivePointsOfItems(itemIds)) {
				byItemId.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
			}
		}
		for (ItemCatalogueLineDTO line : content) {
			Long count = line.isSingle() ? byItemId.get(line.getItemId()) : byNavItemNo.get(line.getNavItemNo());
			line.setStockPoints(count == null ? 0 : count);
		}
	}
}
