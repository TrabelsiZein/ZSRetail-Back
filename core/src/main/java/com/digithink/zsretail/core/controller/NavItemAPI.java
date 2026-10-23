package com.digithink.zsretail.core.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.dto.NavItemDTO;
import com.digithink.zsretail.core.dto.NavItemVariantsDTO;
import com.digithink.zsretail.core.service.NavItemService;

import lombok.RequiredArgsConstructor;

/**
 * Item variants (ZEN), step 11a: the read-only page "NAV items" (store and head office). The NAV item number travels as
 * a query parameter, never in the path: a NAV number may hold a '/' or a space.
 */
@RestController
@RequestMapping("nav-item")
@RequiredArgsConstructor
public class NavItemAPI {

	private final NavItemService navItemService;

	/**
	 * GET /nav-item?page&size&search&status: {content, page, size, totalElements, totalPages, withStock,
	 * withStockPoints}. status: ACTIVE (the default: the active variants only) or ALL.
	 */
	@GetMapping
	public ResponseEntity<Map<String, Object>> page(@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size,
			@RequestParam(name = "search", required = false) String search,
			@RequestParam(name = "status", required = false) String status) {
		Page<NavItemDTO> lines = navItemService.page(search, activeOnly(status), page, size);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("content", lines.getContent());
		response.put("page", lines.getNumber());
		response.put("size", lines.getSize());
		response.put("totalElements", lines.getTotalElements());
		response.put("totalPages", lines.getTotalPages());
		response.put("withStock", navItemService.withStock());
		response.put("withStockPoints", navItemService.withStockPoints());
		return ResponseEntity.ok(response);
	}

	/** GET /nav-item/variants?navItemNo&status: the variants of one NAV item number (status as the page). */
	@GetMapping("variants")
	public ResponseEntity<NavItemVariantsDTO> variants(@RequestParam(name = "navItemNo") String navItemNo,
			@RequestParam(name = "status", required = false) String status) {
		return ResponseEntity.ok(navItemService.variants(navItemNo, activeOnly(status)));
	}

	/**
	 * GET /nav-item/names?navItemNo=A&amp;navItemNo=B: {A: name, B: name}, the name of each NAV item number (promotions on
	 * every variant, step 1b: the target of a promotion on a NAV item number); a number no item carries is left out.
	 */
	@GetMapping("names")
	public ResponseEntity<Map<String, String>> names(
			@RequestParam(name = "navItemNo", required = false) List<String> navItemNos) {
		return ResponseEntity.ok(navItemService.namesOf(navItemNos));
	}

	/** ALL in any case: every variant; anything else (absent, ACTIVE): the active ones only. */
	static boolean activeOnly(String status) {
		return status == null || !"ALL".equalsIgnoreCase(status.trim());
	}
}
