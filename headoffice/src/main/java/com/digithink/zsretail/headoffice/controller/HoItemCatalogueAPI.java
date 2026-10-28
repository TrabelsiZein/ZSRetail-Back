package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.dto.ItemCatalogueFiltersDTO;
import com.digithink.zsretail.core.dto.ItemCatalogueLineDTO;
import com.digithink.zsretail.core.repository.ItemCatalogueQuery;
import com.digithink.zsretail.core.service.NavItemService;
import com.digithink.zsretail.headoffice.repository.HoItemCatalogueQuery;
import com.digithink.zsretail.headoffice.service.HoItemCatalogueService;

import lombok.RequiredArgsConstructor;

/**
 * Fiches items (3.1.0), steps 1 and 2: the list and the item card of the head office page "Fiches items", read only, on
 * every head office (JWT, like the other admin APIs; the page has the Items permission, admin-headoffice-items). See
 * docs/modules/head-office.md, "Fiches items".
 */
@RestController
@RequestMapping("admin/headoffice/item-catalogue")
@RequiredArgsConstructor
public class HoItemCatalogueAPI {

	private final HoItemCatalogueService service;

	/**
	 * GET /admin/headoffice/item-catalogue?page&size&search&status&familyId&subFamilyId&divisionCode&categoryCode
	 * &sectionCode&stockPointId&variants&priceMin&priceMax&sort&dir: {content, page, size, totalElements, totalPages,
	 * withStock, withStockPoints}. status: ACTIVE (the default: the active variants only), ALL, or INACTIVE (the lines
	 * without an active variant, every variant counted); variants: ALL (default),
	 * YES (2 variants or more of the status), NO; priceMin / priceMax: the item's own price with VAT; sort: code (default,
	 * natural for the codes of digits only), name, family, variants, price (lines without a price last); dir: asc
	 * (default) or desc. Every filter optional.
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> page(@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size,
			@RequestParam(name = "search", required = false) String search,
			@RequestParam(name = "status", required = false) String status,
			@RequestParam(name = "familyId", required = false) Long familyId,
			@RequestParam(name = "subFamilyId", required = false) Long subFamilyId,
			@RequestParam(name = "divisionCode", required = false) String divisionCode,
			@RequestParam(name = "categoryCode", required = false) String categoryCode,
			@RequestParam(name = "sectionCode", required = false) String sectionCode,
			@RequestParam(name = "stockPointId", required = false) Long stockPointId,
			@RequestParam(name = "variants", required = false) String variants,
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
		criteria.setDivisionCode(divisionCode);
		criteria.setCategoryCode(categoryCode);
		criteria.setSectionCode(sectionCode);
		HoItemCatalogueQuery.stockPoint(criteria, stockPointId);
		criteria.setVariants(variants);
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
		response.put("withStockPoints", service.withStockPoints());
		return ResponseEntity.ok(response);
	}

	/**
	 * GET /admin/headoffice/item-catalogue/filters: the page's flags (hasVariants, hasFamilies, hasDivision, hasCategory,
	 * hasSection, stockPointCount, hasBarcodes) and filter choices, from the data; read once when the page opens.
	 */
	@GetMapping("filters")
	public ResponseEntity<ItemCatalogueFiltersDTO> filters() {
		return ResponseEntity.ok(service.filters());
	}

	/**
	 * Step 2: GET /admin/headoffice/item-catalogue/card?navItemNo=…|itemId=…[&search=…]: the item card (header, every
	 * variant with its barcodes and point de stock rows, the points, the details). 400 {"error"} unless exactly one of
	 * navItemNo and itemId; 404 {"error":"Not found"} when nothing is found. The NAV number is a query parameter: it may
	 * hold a '/'.
	 */
	@GetMapping("card")
	public ResponseEntity<?> card(@RequestParam(name = "navItemNo", required = false) String navItemNo,
			@RequestParam(name = "itemId", required = false) Long itemId,
			@RequestParam(name = "search", required = false) String search) {
		try {
			return service.card(navItemNo, itemId, search).<ResponseEntity<?>>map(ResponseEntity::ok)
					.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("Not found")));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}

	/** ALL or INACTIVE in any case: every variant; anything else (absent, ACTIVE): the active ones only. */
	static boolean activeOnly(String status) {
		return status == null || !("ALL".equalsIgnoreCase(status.trim()) || inactiveOnly(status));
	}

	/** INACTIVE in any case: only the lines without an active variant. */
	static boolean inactiveOnly(String status) {
		return status != null && "INACTIVE".equalsIgnoreCase(status.trim());
	}
}
