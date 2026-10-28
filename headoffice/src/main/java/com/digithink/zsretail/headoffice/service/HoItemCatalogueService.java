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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.dto.ItemCatalogueFiltersDTO;
import com.digithink.zsretail.core.dto.ItemCatalogueLineDTO;
import com.digithink.zsretail.core.dto.NavItemVariantsDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemCatalogueQuery;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.service.ItemCatalogueService;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.headoffice.dto.ItemCatalogueCardDTO;
import com.digithink.zsretail.headoffice.repository.HoItemCatalogueQuery;
import com.digithink.zsretail.headoffice.repository.HoItemCatalogueRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;
import com.digithink.zsretail.headoffice.repository.HoStoreStockRepository;

import lombok.RequiredArgsConstructor;

/**
 * Fiches items (3.1.0), step 1: the list of the head office page "Fiches items" (docs/modules/head-office.md), on every
 * head office: one line per NAV item number, else one per item, grouped and paged by the database. Since 3.1.1 the list,
 * its flags and its choices are core's ({@link ItemCatalogueService}, shared with the store's Articles page); the head
 * office adds its points de stock: the filter ({@link HoItemCatalogueQuery#stockPoint}), the count and choices of the
 * page, and each line's count of active points (ho_stock_point_item, 0 on a head office without points de stock). The
 * item card (step 2) is the head office's own.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class HoItemCatalogueService {

	private final ItemCatalogueService catalogue;
	private final ItemCatalogueQuery query;
	private final HoItemCatalogueRepository lines;
	private final HoStockPointItemRepository rows;
	private final HoStockPointRepository points;
	private final ItemCategoryRepository categoryRepository;
	private final ItemSectionRepository sectionRepository;
	private final ItemRepository itemRepository;
	private final ItemBarcodeRepository barcodeRepository;
	private final HoNavItemStockPoints pointPrices;
	private final HoStoreStockRepository storeStocks;

	/** The page's flags and filter choices, from the data (read once when the page opens), with the points de stock. */
	public ItemCatalogueFiltersDTO filters() {
		ItemCatalogueFiltersDTO filters = catalogue.filters();
		Object count = query.rows(HoItemCatalogueQuery.STOCK_POINT_COUNT).get(0)[0];
		filters.setStockPointCount(count == null ? 0 : ((Number) count).longValue());
		if (filters.getStockPointCount() > 0) {
			for (Object[] row : query.rows(HoItemCatalogueQuery.STOCK_POINTS)) {
				filters.getStockPoints().add(new ItemCatalogueFiltersDTO.Option(
						row[0] == null ? null : ((Number) row[0]).longValue(), (String) row[1], (String) row[2], null, null));
			}
		}
		return filters;
	}

	/** True when the lines carry the stock (the head office keeps its own). */
	public boolean withStock() {
		return catalogue.withStock();
	}

	/** True when a point de stock exists (the page's point de stock column and tab). */
	public boolean withStockPoints() {
		return points.count() > 0;
	}

	/**
	 * One page of lines of the criteria (the search already as a like, see NavItemService.likeOf; the point de stock
	 * filter set by HoItemCatalogueQuery.stockPoint), each with its count of active points de stock.
	 */
	public Page<ItemCatalogueLineDTO> page(ItemCatalogueQuery.Criteria criteria, int page, int size) {
		Page<ItemCatalogueLineDTO> found = catalogue.page(criteria, page, size);
		pointCounts(found.getContent());
		return found;
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
