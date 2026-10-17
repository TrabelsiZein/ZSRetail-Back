package com.digithink.zsretail.core.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.NavItemDTO;
import com.digithink.zsretail.core.dto.NavItemVariantsDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;

/**
 * Item variants (ZEN), step 11a: the read-only page "NAV items", one line per NAV item number (the variants are items
 * sharing it, docs/variants-zen-analysis-2026-10-14.md), grouped and paged by the database. The stock is given only
 * where the installation keeps its own ({@link ApplicationModeService#keepsOwnStock()}); the point de stock part only
 * where the head office's {@link NavItemStockPoints} exists. An installation without NAV item numbers (EMTOP, Happyness)
 * has no line and no menu entry ({@link #hasNavItems()}).
 */
@Service
@Transactional(readOnly = true)
public class NavItemService {

	/** Lines per page at most. */
	static final int MAX_SIZE = 200;

	private final ItemRepository itemRepository;
	private final ItemCategoryRepository categoryRepository;
	private final ItemSectionRepository sectionRepository;
	private final ApplicationModeService mode;
	private final NavItemStockPoints stockPoints;

	public NavItemService(ItemRepository itemRepository, ItemCategoryRepository categoryRepository,
			ItemSectionRepository sectionRepository, ApplicationModeService mode,
			Optional<NavItemStockPoints> stockPoints) {
		this.itemRepository = itemRepository;
		this.categoryRepository = categoryRepository;
		this.sectionRepository = sectionRepository;
		this.mode = mode;
		this.stockPoints = stockPoints.orElse(null);
	}

	/** True when at least one item carries a NAV item number: the "NAV items" menu entry (GET /config navItems). */
	public boolean hasNavItems() {
		return itemRepository.existsByNavItemNoIsNotNull();
	}

	/** True on a head office (the point de stock bean exists): the lines carry their points, the variants a price grid. */
	public boolean withStockPoints() {
		return stockPoints != null;
	}

	/** True when the lines and variants carry the stock (the installation keeps its own). */
	public boolean withStock() {
		return mode.keepsOwnStock();
	}

	/**
	 * One page of lines by NAV item number. search: NAV item number or name, contains, any case; activeOnly: the active
	 * variants only (a NAV item whose variants are all inactive has no line), else every variant.
	 */
	public Page<NavItemDTO> page(String search, boolean activeOnly, int page, int size) {
		Page<Object[]> rows = itemRepository.findNavItems(likeOf(search), activeOnly ? Boolean.TRUE : null,
				PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE)));
		List<NavItemDTO> lines = rows.getContent().stream().map(this::line).collect(Collectors.toList());
		names(lines);
		if (stockPoints != null && !lines.isEmpty()) {
			Map<String, Long> counts = stockPoints
					.activePointCounts(lines.stream().map(NavItemDTO::getNavItemNo).collect(Collectors.toList()));
			lines.forEach(line -> line.setStockPoints(counts.getOrDefault(line.getNavItemNo(), 0L)));
		}
		return new PageImpl<>(lines, rows.getPageable(), rows.getTotalElements());
	}

	/** The variants of one NAV item number (activeOnly as {@link #page}), with their rows on a head office. */
	public NavItemVariantsDTO variants(String navItemNo, boolean activeOnly) {
		NavItemVariantsDTO answer = new NavItemVariantsDTO();
		if (navItemNo == null || navItemNo.trim().isEmpty()) {
			return answer;
		}
		List<Item> items = itemRepository.findNavItemVariants(navItemNo.trim(), activeOnly ? Boolean.TRUE : null);
		boolean withStock = withStock();
		for (Item item : items) {
			answer.getVariants().add(variant(item, withStock));
		}
		if (stockPoints != null) {
			answer.setStockPoints(new ArrayList<>());
			if (!items.isEmpty()) {
				Map<Long, Map<Long, NavItemVariantsDTO.PointPrice>> prices = stockPoints
						.prices(items.stream().map(Item::getId).collect(Collectors.toList()));
				Set<Long> pointIds = new LinkedHashSet<>();
				for (NavItemVariantsDTO.Variant variant : answer.getVariants()) {
					Map<Long, NavItemVariantsDTO.PointPrice> rows = prices.getOrDefault(variant.getId(),
							Collections.emptyMap());
					rows.forEach(variant::putPrice);
					pointIds.addAll(rows.keySet());
				}
				if (!pointIds.isEmpty()) {
					answer.setStockPoints(stockPoints.points(pointIds));
				}
			}
		}
		return answer;
	}

	private NavItemDTO line(Object[] row) {
		NavItemDTO line = new NavItemDTO();
		line.setNavItemNo((String) row[0]);
		line.setName((String) row[1]);
		line.setFamilyName((String) row[2]);
		line.setSubFamilyName((String) row[3]);
		line.setCategoryCode((String) row[4]);
		line.setSectionCode((String) row[5]);
		line.setVariants(row[6] == null ? 0 : ((Number) row[6]).longValue());
		if (withStock()) {
			line.setStockQuantity(row[7] == null ? BigDecimal.ZERO : (BigDecimal) row[7]);
		}
		return line;
	}

	/** The catégorie and section names of the page's codes, one read each. */
	private void names(List<NavItemDTO> lines) {
		Set<String> categoryCodes = lines.stream().map(NavItemDTO::getCategoryCode).filter(Objects::nonNull)
				.collect(Collectors.toSet());
		Set<String> sectionCodes = lines.stream().map(NavItemDTO::getSectionCode).filter(Objects::nonNull)
				.collect(Collectors.toSet());
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
		for (NavItemDTO line : lines) {
			line.setCategoryName(categories.get(line.getCategoryCode()));
			line.setSectionName(sections.get(line.getSectionCode()));
		}
	}

	private static NavItemVariantsDTO.Variant variant(Item item, boolean withStock) {
		NavItemVariantsDTO.Variant variant = new NavItemVariantsDTO.Variant();
		variant.setId(item.getId());
		variant.setItemCode(item.getItemCode());
		variant.setVariantCode(item.getVariantCode());
		variant.setVariantDescription(item.getVariantDescription());
		variant.setSizeCode(item.getSizeCode());
		variant.setSizeDescription(item.getSizeDescription());
		variant.setColorCode(item.getColorCode());
		variant.setColorDescription(item.getColorDescription());
		variant.setChoiceCode(item.getChoiceCode());
		variant.setChoiceDescription(item.getChoiceDescription());
		variant.setActive(item.getActive() == null || item.getActive());
		variant.setUnitPrice(item.getUnitPrice());
		variant.setDefaultVAT(item.getDefaultVAT());
		variant.setClearance(item.getClearance());
		variant.setPublicPrice(item.getPublicPrice());
		variant.setOwnPrice(item.getOwnPrice());
		if (withStock) {
			variant.setStockQuantity(item.getStockQuantity() == null ? BigDecimal.ZERO : item.getStockQuantity());
		}
		return variant;
	}

	/** Lower case with % around it, null when blank. */
	public static String likeOf(String search) {
		return search == null || search.trim().isEmpty() ? null
				: "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
	}
}
