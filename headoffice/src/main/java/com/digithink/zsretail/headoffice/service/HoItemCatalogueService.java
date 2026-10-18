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
import com.digithink.zsretail.headoffice.dto.ItemCatalogueCardDTO;
import com.digithink.zsretail.headoffice.dto.ItemCatalogueLineDTO;
import com.digithink.zsretail.headoffice.repository.HoItemCatalogueRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;

import lombok.RequiredArgsConstructor;

/**
 * Fiches items (3.1.0), step 1: the list of the head office page "Fiches items" (docs/modules/head-office.md), on every
 * head office: one line per NAV item number, else one per item, grouped and paged by the database
 * ({@link HoItemCatalogueRepository}). The stock is given only where the head office keeps its own; the point de stock
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
		String like = NavItemService.likeOf(search);
		int allStatuses = activeOnly ? 0 : 1;
		PageRequest paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
		// The barcode search only in the query of a search, as Items by point de stock (2fe0b34)
		Page<Object[]> found = like == null ? lines.findLines(allStatuses, paging)
				: lines.findLinesSearched(like, allStatuses, paging);
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
		card.setDetails(details(highlighted != null ? highlighted : items.get(0)));
		return Optional.of(card);
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

	private static ItemCatalogueCardDTO.Details details(Item item) {
		ItemCatalogueCardDTO.Details details = new ItemCatalogueCardDTO.Details();
		details.setItemId(item.getId());
		details.setDescription(item.getDescription());
		details.setUnitOfMeasure(item.getUnitOfMeasure());
		details.setCategory(item.getCategory());
		details.setBrand(item.getBrand());
		details.setItemDiscGroup(item.getItemDiscGroup());
		details.setMaximumAuthorizedDiscount(item.getMaximumAuthorizedDiscount());
		details.setCostPrice(item.getCostPrice());
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
	 * One row of {@link HoItemCatalogueRepository#SELECT} as a line, read by the repository's column constants (the order
	 * of its aliases, {@link HoItemCatalogueRepository#LINE_COLUMNS}); public for the query test.
	 */
	public static ItemCatalogueLineDTO line(Object[] row, boolean withStock) {
		ItemCatalogueLineDTO line = new ItemCatalogueLineDTO();
		String navItemNo = (String) row[HoItemCatalogueRepository.COL_NAV_ITEM_NO];
		line.setNavItemNo(navItemNo);
		line.setSingle(navItemNo == null);
		if (navItemNo == null) {
			line.setItemId(((Number) row[HoItemCatalogueRepository.COL_ITEM_ID]).longValue());
			line.setCode((String) row[HoItemCatalogueRepository.COL_ITEM_CODE]);
		} else {
			line.setCode(navItemNo);
		}
		line.setName((String) row[HoItemCatalogueRepository.COL_ITEM_NAME]);
		line.setFamilyName((String) row[HoItemCatalogueRepository.COL_FAMILY_NAME]);
		line.setSubFamilyName((String) row[HoItemCatalogueRepository.COL_SUB_FAMILY_NAME]);
		line.setCategoryCode((String) row[HoItemCatalogueRepository.COL_CATEGORY_CODE]);
		line.setSectionCode((String) row[HoItemCatalogueRepository.COL_SECTION_CODE]);
		Object variants = row[HoItemCatalogueRepository.COL_VARIANT_COUNT];
		line.setVariants(variants == null ? 0 : ((Number) variants).longValue());
		Object anyActive = row[HoItemCatalogueRepository.COL_ANY_ACTIVE];
		line.setActive(anyActive != null && ((Number) anyActive).intValue() == 1);
		if (withStock) {
			Object stock = row[HoItemCatalogueRepository.COL_STOCK_QUANTITY];
			line.setStockQuantity(stock == null ? BigDecimal.ZERO : new BigDecimal(stock.toString()));
		}
		return line;
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
