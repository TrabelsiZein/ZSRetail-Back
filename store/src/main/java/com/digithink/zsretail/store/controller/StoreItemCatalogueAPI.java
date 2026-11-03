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
import com.digithink.zsretail.core.dto.ItemExportDTO;
import com.digithink.zsretail.core.repository.ItemCatalogueQuery;
import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.controller.ItemAPI;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.ItemCatalogueService;
import com.digithink.zsretail.core.service.NavItemService;

import lombok.RequiredArgsConstructor;

/**
 * 3.1.1, the store's Articles page where items carry NAV numbers; 3.1.2, on every store (docs/admin-pages.md): the head
 * office's item catalogue list (core, {@link ItemCatalogueService}) read at the store. One line per NAV item number,
 * else one per item, with each NAV item's variants read from GET /nav-item/variants. No point de stock at a store.
 */
@RestController
@RequestMapping("item-catalogue")
@RequiredArgsConstructor
public class StoreItemCatalogueAPI {

	private final ItemCatalogueService service;

	private final ApplicationModeService mode;

	private final CurrentUserProvider currentUserProvider;

	/**
	 * GET /item-catalogue?page&amp;size&amp;search&amp;status&amp;familyId&amp;subFamilyId&amp;categoryCode&amp;sectionCode
	 * &amp;priceMin&amp;priceMax&amp;sort&amp;dir&amp;withoutCost: {content, page, size, totalElements, totalPages, withStock,
	 * withCost}. withoutCost (3.1.2): true for the items without a CMP only (WITHOUT_COST), only with withCost. search:
	 * NAV number, item code (a variant's), name or barcode, contains, any case; with it each line gives matchedItemIds,
	 * its items found. status: ALL (the default, as the Articles page), ACTIVE (the active variants only) or INACTIVE (the
	 * lines without an active variant); priceMin / priceMax: the item's own price with VAT; sort: code (default), name,
	 * family, variants, price, stock (3.1.2); dir: asc (default) or desc. Every filter optional. 3.1.2: each line also gives what
	 * the page shows beyond the grouped columns (ItemCatalogueService.storeDetails: a single item's origin, own price,
	 * price before VAT and VAT, clearance public price, minimum stock, maximum discount) and, with withCost, the line's
	 * weighted average cost (costMin / costMax).
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
			@RequestParam(name = "dir", required = false) String dir,
			@RequestParam(name = "withoutCost", required = false) Boolean withoutCost) {
		// 3.1.2: what the lines show beyond the grouped columns, the cost only to a role that may see it
		boolean withCost = withCost();
		ItemCatalogueQuery.Criteria criteria = criteria(search, status, familyId, subFamilyId, categoryCode, sectionCode,
				priceMin, priceMax, sort, dir, withCost && Boolean.TRUE.equals(withoutCost));
		Page<ItemCatalogueLineDTO> lines = service.page(criteria, page, size);
		service.storeDetails(lines.getContent(), withCost);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("content", lines.getContent());
		response.put("page", lines.getNumber());
		response.put("size", lines.getSize());
		response.put("totalElements", lines.getTotalElements());
		response.put("totalPages", lines.getTotalPages());
		response.put("withStock", service.withStock());
		response.put("withCost", withCost);
		return ResponseEntity.ok(response);
	}

	/**
	 * 3.1.2, the Excel export of the Articles page: GET /item-catalogue/export with the list's filters and sort (no
	 * page): {limit, truncated, withStock, withCost, rows}, the items of the lines the list shows, one row each (a NAV
	 * item's variants of the status), at most {@link ItemCatalogueService#EXPORT_MAX}; the cost as the list's.
	 */
	@GetMapping("export")
	public ResponseEntity<ItemExportDTO> export(@RequestParam(name = "search", required = false) String search,
			@RequestParam(name = "status", required = false) String status,
			@RequestParam(name = "familyId", required = false) Long familyId,
			@RequestParam(name = "subFamilyId", required = false) Long subFamilyId,
			@RequestParam(name = "categoryCode", required = false) String categoryCode,
			@RequestParam(name = "sectionCode", required = false) String sectionCode,
			@RequestParam(name = "priceMin", required = false) Double priceMin,
			@RequestParam(name = "priceMax", required = false) Double priceMax,
			@RequestParam(name = "sort", required = false) String sort,
			@RequestParam(name = "dir", required = false) String dir,
			@RequestParam(name = "withoutCost", required = false) Boolean withoutCost) {
		boolean withCost = withCost();
		ItemCatalogueQuery.Criteria criteria = criteria(search, status, familyId, subFamilyId, categoryCode, sectionCode,
				priceMin, priceMax, sort, dir, withCost && Boolean.TRUE.equals(withoutCost));
		return ResponseEntity.ok(service.export(criteria, withCost, ItemCatalogueService.EXPORT_MAX));
	}

	/** The list's criteria of the request's filters, the same for its page and its export. */
	static ItemCatalogueQuery.Criteria criteria(String search, String status, Long familyId, Long subFamilyId,
			String categoryCode, String sectionCode, Double priceMin, Double priceMax, String sort, String dir,
			boolean withoutCost) {
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
		if (withoutCost) {
			criteria.getItemConditions().add(WITHOUT_COST);
		}
		return criteria;
	}

	/** GET /item-catalogue/filters: the page's flags and filter choices, from the data; read once when the page opens. */
	@GetMapping("filters")
	public ResponseEntity<ItemCatalogueFiltersDTO> filters() {
		return ResponseEntity.ok(service.filters());
	}

	/**
	 * 3.1.2: the lines carry the weighted average cost only where the store keeps its own stock (the CMP is kept there)
	 * and for a role with write:admin-item-cost ("Ajuster le CMP") or read:admin-margins (the line costs of a ticket).
	 */
	boolean withCost() {
		return mode.keepsOwnStock() && currentUserProvider != null
				&& (currentUserProvider.currentUserHasPermission(ItemAPI.ADJUST_COST_PERMISSION)
						|| currentUserProvider.currentUserHasPermission(SalesHeaderAPI.MARGINS_PERMISSION));
	}

	/**
	 * 3.1.2, the filter "Sans coût" (withoutCost=true, read only with withCost): the items without a CMP whose cost the
	 * owner can set, a service or a discount item (no cost by nature, ItemType.carriesNoCost; the tax stamp is a service)
	 * and a pack (its components carry the cost; "Ajuster le CMP" is not offered on it) left out.
	 */
	static final String WITHOUT_COST = "({a}.cost_price IS NULL AND ({a}.type IS NULL OR {a}.type NOT IN ('SERVICE', 'DISCOUNT', 'PACKAGE')))";

	/** ACTIVE in any case: the active variants only; anything else (absent, ALL, INACTIVE): every variant. */
	static boolean activeOnly(String status) {
		return status != null && "ACTIVE".equalsIgnoreCase(status.trim());
	}

	/** INACTIVE in any case: only the lines without an active variant. */
	static boolean inactiveOnly(String status) {
		return status != null && "INACTIVE".equalsIgnoreCase(status.trim());
	}
}
