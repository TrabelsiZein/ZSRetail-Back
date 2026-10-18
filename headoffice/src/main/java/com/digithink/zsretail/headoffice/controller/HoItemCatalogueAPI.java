package com.digithink.zsretail.headoffice.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.headoffice.dto.ItemCatalogueLineDTO;
import com.digithink.zsretail.headoffice.service.HoItemCatalogueService;

import lombok.RequiredArgsConstructor;

/**
 * Fiches items (3.1.0), step 1: the list of the head office page "Fiches items", read only, on every head office (JWT,
 * like the other admin APIs; the page has the Items permission, admin-headoffice-items). See docs/modules/head-office.md,
 * "Fiches items".
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

	/** ALL in any case: every variant; anything else (absent, ACTIVE): the active ones only. */
	static boolean activeOnly(String status) {
		return status == null || !"ALL".equalsIgnoreCase(status.trim());
	}
}
