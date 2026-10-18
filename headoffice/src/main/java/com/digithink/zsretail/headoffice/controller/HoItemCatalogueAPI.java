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

import com.digithink.zsretail.headoffice.dto.ItemCatalogueLineDTO;
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
	 * GET /admin/headoffice/item-catalogue?page&size&search&status: {content, page, size, totalElements, totalPages,
	 * withStock, withStockPoints}. status: ACTIVE (the default: the active variants only) or ALL.
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> page(@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size,
			@RequestParam(name = "search", required = false) String search,
			@RequestParam(name = "status", required = false) String status) {
		Page<ItemCatalogueLineDTO> lines = service.page(search, activeOnly(status), page, size);
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

	/** ALL in any case: every variant; anything else (absent, ACTIVE): the active ones only. */
	static boolean activeOnly(String status) {
		return status == null || !"ALL".equalsIgnoreCase(status.trim());
	}
}
