package com.digithink.zsretail.store.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.dto.ItemCatalogueFiltersDTO;
import com.digithink.zsretail.core.dto.ItemCatalogueLineDTO;
import com.digithink.zsretail.core.repository.ItemCatalogueQuery;
import com.digithink.zsretail.core.service.ItemCatalogueService;
import com.digithink.zsretail.core.service.NavItemService;

import lombok.RequiredArgsConstructor;

/**
 * 3.1.1, the store's Articles page where items carry NAV numbers (the grouped view, docs/admin-pages.md): the head
 * office's item catalogue list (core, {@link ItemCatalogueService}) read at the store. One line per NAV item number,
 * else one per item (the store's own items), with each NAV item's variants read from GET /nav-item/variants. No point de
 * stock at a store.
 */
@RestController
@RequestMapping("item-catalogue")
@RequiredArgsConstructor
public class StoreItemCatalogueAPI {

	private final ItemCatalogueService service;

	/**
	 * GET /item-catalogue?page&amp;size&amp;search&amp;status&amp;familyId&amp;subFamilyId&amp;categoryCode&amp;sectionCode
	 * &amp;priceMin&amp;priceMax&amp;sort&amp;dir: {content, page, size, totalElements, totalPages, withStock}. search:
	 * NAV number, item code (a variant's), name or barcode, contains, any case; with it each line gives matchedItemIds,
	 * its items found. status: ALL (the default, as the Articles page), ACTIVE (the active variants only) or INACTIVE (the
	 * lines without an active variant); priceMin / priceMax: the item's own price with VAT; sort: code (default), name,
	 * family, variants, price; dir: asc (default) or desc. Every filter optional.
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> page(@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size,
			@RequestParam(name = "search", required = false) String search,
			@RequestParam(name = "status", required = false) String status,
			@RequestParam(name = "familyId", required = false) Long familyId,
			@RequestParam(name = "subFamilyId", required = false) Long subFamilyId,
			@RequestParam(name = "categoryCode", required = false) String categoryCode,
			@RequestParam(name = "sectionCode", required = false) String sectionCode,
			@RequestParam(name = "priceMin", required = false) Double priceMin,
			@RequestParam(name = "priceMax", required = false) Double priceMax,
			@RequestParam(name = "sort", required = false) String sort,
			@RequestParam(name = "dir", required = false) String dir) {
		ItemCatalogueQuery.Criteria criteria = new ItemCatalogueQuery.Criteria();
		criteria.setLike(NavItemService.likeOf(search));
		criteria.setActiveOnly(activeOnly(status));
		criteria.setInactiveOnly(inactiveOnly(status));
		criteria.setFamilyId(familyId);
		criteria.setSubFamilyId(subFamilyId);
		criteria.setCategoryCode(categoryCode);
		criteria.setSectionCode(sectionCode);
		criteria.setPriceMin(priceMin);
		criteria.setPriceMax(priceMax);
		criteria.setSort(sort);
		criteria.setDescending("desc".equalsIgnoreCase(dir == null ? null : dir.trim()));
		Page<ItemCatalogueLineDTO> lines = service.page(criteria, page, size);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("content", lines.getContent());
		response.put("page", lines.getNumber());
		response.put("size", lines.getSize());
		response.put("totalElements", lines.getTotalElements());
		response.put("totalPages", lines.getTotalPages());
		response.put("withStock", service.withStock());
		return ResponseEntity.ok(response);
	}

	/** GET /item-catalogue/filters: the page's flags and filter choices, from the data; read once when the page opens. */
	@GetMapping("filters")
	public ResponseEntity<ItemCatalogueFiltersDTO> filters() {
		return ResponseEntity.ok(service.filters());
	}

	/** ACTIVE in any case: the active variants only; anything else (absent, ALL, INACTIVE): every variant. */
	static boolean activeOnly(String status) {
		return status != null && "ACTIVE".equalsIgnoreCase(status.trim());
	}

	/** INACTIVE in any case: only the lines without an active variant. */
	static boolean inactiveOnly(String status) {
		return status != null && "INACTIVE".equalsIgnoreCase(status.trim());
	}
}
