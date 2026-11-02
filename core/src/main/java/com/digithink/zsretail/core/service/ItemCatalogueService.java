package com.digithink.zsretail.core.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.ItemCatalogueFiltersDTO;
import com.digithink.zsretail.core.dto.ItemCatalogueLineDTO;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.repository.ItemCatalogueQuery;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Fiches items (3.1.0), in core since 3.1.1: the grouped list of the items, one line per NAV item number, else one per
 * item, grouped and paged by the database ({@link ItemCatalogueQuery}), and the page's flags and filter choices. Read by
 * the head office page "Fiches items" (HoItemCatalogueService adds its points de stock) and by the store's Articles page
 * where items carry NAV numbers (StoreItemCatalogueAPI). The stock is given only where the installation keeps its own.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ItemCatalogueService {

	/** Lines per page at most. */
	public static final int MAX_SIZE = 200;

	private final ItemCatalogueQuery query;
	private final ItemCategoryRepository categoryRepository;
	private final ItemSectionRepository sectionRepository;
	private final ApplicationModeService mode;

	/** The page's flags and filter choices, from the data (read once when the page opens); no point de stock. */
	public ItemCatalogueFiltersDTO filters() {
		ItemCatalogueFiltersDTO filters = new ItemCatalogueFiltersDTO();
		Object[] flags = query.rows(ItemCatalogueQuery.FLAGS).get(0);
		filters.setHasVariants(flag(flags[0]));
		filters.setHasFamilies(flag(flags[1]));
		filters.setHasDivision(flag(flags[2]));
		filters.setHasCategory(flag(flags[3]));
		filters.setHasSection(flag(flags[4]));
		filters.setHasBarcodes(flag(flags[5]));
		if (filters.isHasFamilies()) {
			for (Object[] row : query.rows(ItemCatalogueQuery.FAMILIES)) {
				filters.getFamilies().add(
						new ItemCatalogueFiltersDTO.Option(id(row[0]), (String) row[1], (String) row[2], null, (String) row[3]));
			}
			for (Object[] row : query.rows(ItemCatalogueQuery.SUB_FAMILIES)) {
				filters.getSubFamilies()
						.add(new ItemCatalogueFiltersDTO.Option(id(row[0]), (String) row[1], (String) row[2], id(row[3]), null));
			}
		}
		if (filters.isHasDivision()) {
			for (Object[] row : query.rows(ItemCatalogueQuery.DIVISIONS)) {
				filters.getDivisions().add(new ItemCatalogueFiltersDTO.Option(null, (String) row[0], null, null, null));
			}
		}
		if (filters.isHasCategory()) {
			for (Object[] row : query.rows(ItemCatalogueQuery.CATEGORIES)) {
				filters.getCategories()
						.add(new ItemCatalogueFiltersDTO.Option(null, (String) row[0], (String) row[1], null, null));
			}
		}
		if (filters.isHasSection()) {
			for (Object[] row : query.rows(ItemCatalogueQuery.SECTIONS)) {
				filters.getSections()
						.add(new ItemCatalogueFiltersDTO.Option(null, (String) row[0], (String) row[1], null, (String) row[2]));
			}
		}
		return filters;
	}

	/** True when the lines carry the stock (the installation keeps its own). */
	public boolean withStock() {
		return mode.keepsOwnStock();
	}

	/**
	 * One page of lines of the criteria (the search already as a like, see NavItemService.likeOf), with their catégorie
	 * and section names and, with a search, the ids of each line's items that match it.
	 */
	public Page<ItemCatalogueLineDTO> page(ItemCatalogueQuery.Criteria criteria, int page, int size) {
		PageRequest paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
		// Only the filters set are in the statement (the barcode search only in a search's, 2fe0b34)
		Page<Object[]> found = query.lines(criteria, paging);
		boolean withStock = withStock();
		List<ItemCatalogueLineDTO> content = found.getContent().stream().map(row -> line(row, withStock))
				.collect(Collectors.toList());
		names(content);
		matched(content, criteria.getLike());
		return new PageImpl<>(content, found.getPageable(), found.getTotalElements());
	}

	/**
	 * One row of the query's SELECT as a line, read by the column constants (the order of its aliases,
	 * {@link ItemCatalogueQuery#LINE_COLUMNS}); public for the query tests.
	 */
	public static ItemCatalogueLineDTO line(Object[] row, boolean withStock) {
		ItemCatalogueLineDTO line = new ItemCatalogueLineDTO();
		String navItemNo = (String) row[ItemCatalogueQuery.COL_NAV_ITEM_NO];
		line.setNavItemNo(navItemNo);
		line.setSingle(navItemNo == null);
		if (navItemNo == null) {
			line.setItemId(((Number) row[ItemCatalogueQuery.COL_ITEM_ID]).longValue());
			line.setCode((String) row[ItemCatalogueQuery.COL_ITEM_CODE]);
		} else {
			line.setCode(navItemNo);
		}
		line.setName((String) row[ItemCatalogueQuery.COL_ITEM_NAME]);
		line.setFamilyName((String) row[ItemCatalogueQuery.COL_FAMILY_NAME]);
		line.setSubFamilyName((String) row[ItemCatalogueQuery.COL_SUB_FAMILY_NAME]);
		line.setCategoryCode((String) row[ItemCatalogueQuery.COL_CATEGORY_CODE]);
		line.setSectionCode((String) row[ItemCatalogueQuery.COL_SECTION_CODE]);
		Object variants = row[ItemCatalogueQuery.COL_VARIANT_COUNT];
		line.setVariants(variants == null ? 0 : ((Number) variants).longValue());
		Object anyActive = row[ItemCatalogueQuery.COL_ANY_ACTIVE];
		line.setActive(anyActive != null && ((Number) anyActive).intValue() == 1);
		if (withStock) {
			Object stock = row[ItemCatalogueQuery.COL_STOCK_QUANTITY];
			line.setStockQuantity(stock == null ? BigDecimal.ZERO : new BigDecimal(stock.toString()));
		}
		line.setPriceMin(price(row[ItemCatalogueQuery.COL_PRICE_MIN]));
		line.setPriceMax(price(row[ItemCatalogueQuery.COL_PRICE_MAX]));
		return line;
	}

	/** A price with VAT, to the third decimal (the SQL product of a float and a decimal); null stays null. */
	private static Double price(Object value) {
		return value == null ? null : new BigDecimal(value.toString()).setScale(3, RoundingMode.HALF_UP).doubleValue();
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

	/**
	 * With a search: each line's items that match it (one read for the page), in item id order; an empty list for a line
	 * none of whose items matches by itself. Without a search the lines keep null.
	 */
	private void matched(List<ItemCatalogueLineDTO> content, String like) {
		if (like == null || content.isEmpty()) {
			return;
		}
		List<String> navItemNos = content.stream().map(ItemCatalogueLineDTO::getNavItemNo).filter(Objects::nonNull)
				.collect(Collectors.toList());
		List<Long> itemIds = content.stream().filter(ItemCatalogueLineDTO::isSingle).map(ItemCatalogueLineDTO::getItemId)
				.collect(Collectors.toList());
		Map<String, List<Long>> byNavItemNo = new LinkedHashMap<>();
		Set<Long> singles = new HashSet<>();
		for (Object[] row : query.matchedItems(like, navItemNos, itemIds)) {
			long id = ((Number) row[0]).longValue();
			String navItemNo = (String) row[1];
			if (navItemNo == null) {
				singles.add(id);
			} else {
				byNavItemNo.computeIfAbsent(navItemNo, key -> new ArrayList<>()).add(id);
			}
		}
		for (ItemCatalogueLineDTO line : content) {
			if (line.isSingle()) {
				List<Long> ids = new ArrayList<>();
				if (singles.contains(line.getItemId())) {
					ids.add(line.getItemId());
				}
				line.setMatchedItemIds(ids);
			} else {
				line.setMatchedItemIds(byNavItemNo.getOrDefault(line.getNavItemNo(), new ArrayList<>()));
			}
		}
	}

	/**
	 * 3.1.2, the store's Articles page (every store): what its lines show beyond the grouped columns, one read for the
	 * page ({@link ItemCatalogueQuery#details}). A single item: its origin, own price (and the head office's price with
	 * VAT), its price before VAT and VAT %, its public price with VAT when it is in clearance and sold below it, its minimum
	 * stock level and the ERP's maximum discount. With withCost (the caller checks the installation and the user's right),
	 * every line: the smallest and largest weighted average cost of its items, before VAT. The head office never calls it.
	 */
	public void storeDetails(List<ItemCatalogueLineDTO> content, boolean withCost) {
		if (content.isEmpty()) {
			return;
		}
		List<String> navItemNos = content.stream().map(ItemCatalogueLineDTO::getNavItemNo).filter(Objects::nonNull)
				.collect(Collectors.toList());
		List<Long> itemIds = content.stream().filter(ItemCatalogueLineDTO::isSingle).map(ItemCatalogueLineDTO::getItemId)
				.collect(Collectors.toList());
		Map<Long, Object[]> singles = new HashMap<>();
		Map<String, List<Object[]>> byNavItemNo = new HashMap<>();
		for (Object[] row : query.detailRows(navItemNos, itemIds)) {
			String navItemNo = (String) row[ItemCatalogueQuery.DET_NAV_ITEM_NO];
			if (navItemNo == null) {
				singles.put(((Number) row[ItemCatalogueQuery.DET_ITEM_ID]).longValue(), row);
			} else {
				byNavItemNo.computeIfAbsent(navItemNo, key -> new ArrayList<>()).add(row);
			}
		}
		for (ItemCatalogueLineDTO line : content) {
			List<Object[]> rows;
			if (line.isSingle()) {
				Object[] row = singles.get(line.getItemId());
				if (row == null) {
					continue;
				}
				single(line, row);
				rows = Collections.singletonList(row);
			} else {
				rows = byNavItemNo.getOrDefault(line.getNavItemNo(), Collections.emptyList());
			}
			if (withCost) {
				rows.stream().map(row -> number(row[ItemCatalogueQuery.DET_COST_PRICE])).filter(Objects::nonNull)
						.forEach(cost -> {
							line.setCostMin(line.getCostMin() == null ? cost : Math.min(line.getCostMin(), cost));
							line.setCostMax(line.getCostMax() == null ? cost : Math.max(line.getCostMax(), cost));
						});
			}
		}
	}

	/** The fields of a single item's line from its row of {@link ItemCatalogueQuery#details}. */
	static void single(ItemCatalogueLineDTO line, Object[] row) {
		Object origin = row[ItemCatalogueQuery.DET_ORIGIN];
		line.setOrigin(origin == null ? null : origin.toString());
		Double unitPrice = number(row[ItemCatalogueQuery.DET_UNIT_PRICE]);
		Double vat = number(row[ItemCatalogueQuery.DET_VAT_PERCENT]);
		double withVat = 1 + (vat == null ? 0 : vat) / 100.0;
		line.setUnitPrice(unitPrice);
		line.setVatPercent(vat == null ? null : vat.intValue());
		if (truth(row[ItemCatalogueQuery.DET_OWN_PRICE])) {
			line.setOwnPrice(Boolean.TRUE);
			Double headOffice = number(row[ItemCatalogueQuery.DET_HEAD_OFFICE_PRICE]);
			line.setHeadOfficePrice(headOffice == null ? null : price(headOffice * withVat));
		}
		// In clearance, sold below the public price (the till's rule, clearanceShown): that price struck
		Double publicPrice = number(row[ItemCatalogueQuery.DET_PUBLIC_PRICE]);
		if (truth(row[ItemCatalogueQuery.DET_CLEARANCE]) && publicPrice != null
				&& publicPrice > (unitPrice == null ? 0 : unitPrice)) {
			line.setPublicPrice(price(publicPrice * withVat));
		}
		Double minStock = number(row[ItemCatalogueQuery.DET_MIN_STOCK_LEVEL]);
		line.setMinStockLevel(minStock == null ? null : minStock.intValue());
		line.setMaximumAuthorizedDiscount(number(row[ItemCatalogueQuery.DET_MAX_DISCOUNT]));
	}

	/** A bit column: SQL Server gives a Boolean, H2 and a CASE a number. */
	private static boolean truth(Object value) {
		return value instanceof Boolean ? (Boolean) value : value instanceof Number && ((Number) value).intValue() == 1;
	}

	private static Double number(Object value) {
		return value == null ? null : ((Number) value).doubleValue();
	}

	private static boolean flag(Object value) {
		return value != null && ((Number) value).intValue() == 1;
	}

	private static Long id(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}
}
