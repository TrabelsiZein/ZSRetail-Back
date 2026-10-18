package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;
import java.util.HashMap;
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
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.service.NavItemService;
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

	/** One row of {@link HoItemCatalogueRepository#SELECT} as a line (public for the query test). */
	public static ItemCatalogueLineDTO line(Object[] row, boolean withStock) {
		ItemCatalogueLineDTO line = new ItemCatalogueLineDTO();
		String navItemNo = (String) row[0];
		line.setNavItemNo(navItemNo);
		line.setSingle(navItemNo == null);
		if (navItemNo == null) {
			line.setItemId(((Number) row[1]).longValue());
			line.setCode((String) row[2]);
		} else {
			line.setCode(navItemNo);
		}
		line.setName((String) row[3]);
		line.setFamilyName((String) row[4]);
		line.setSubFamilyName((String) row[5]);
		line.setCategoryCode((String) row[6]);
		line.setSectionCode((String) row[7]);
		line.setVariants(row[8] == null ? 0 : ((Number) row[8]).longValue());
		line.setActive(row[9] != null && ((Number) row[9]).intValue() == 1);
		if (withStock) {
			line.setStockQuantity(row[10] == null ? BigDecimal.ZERO : new BigDecimal(row[10].toString()));
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
