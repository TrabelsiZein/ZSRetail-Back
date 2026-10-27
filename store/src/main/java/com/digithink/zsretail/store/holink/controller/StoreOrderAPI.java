package com.digithink.zsretail.store.holink.controller;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.dto.NavItemFilter;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.holink.dto.StoreOrderCatalogueLineDTO;
import com.digithink.zsretail.store.holink.dto.StoreOrderInputDTO;
import com.digithink.zsretail.store.holink.dto.StoreOrderViewDTO;
import com.digithink.zsretail.store.holink.service.StoreOrderService;

import lombok.extern.log4j.Log4j2;

/**
 * 3.1.1: the orders of the store to its head office, "Commandes centrale" (JWT, like the other admin APIs; the pages
 * read:admin-store-orders and write:admin-store-orders). Only on a store whose goods come from the head office:
 * elsewhere these URLs answer 404. The NAV item number travels as a query parameter (it may hold a '/' or a space).
 * Errors {"error"}: 400 invalid data, 404 unknown order or NAV item, 409 an order that is no longer a draft or a refusal
 * of the head office, 503 the head office did not answer (nothing shown, nothing saved). See
 * docs/modules/store-orders.md.
 */
@RestController
@RequestMapping("store-order")
@ConditionalOnInstallation(Installation.SUPPLY_FROM_HEAD_OFFICE)
@Log4j2
public class StoreOrderAPI {

	private static final String NOT_FOUND = "Not found";

	private final StoreOrderService service;
	private final CurrentUserProvider currentUser;

	public StoreOrderAPI(StoreOrderService service, CurrentUserProvider currentUser) {
		this.service = service;
		this.currentUser = currentUser;
	}

	/**
	 * GET /store-order/catalogue?page&amp;size&amp;search&amp;categoryCode&amp;familyId&amp;subFamilyId&amp;sectionCode&amp;sort&amp;dir:
	 * {content, page, size, totalElements, totalPages}, one line per NAV item number with an active variant. search: NAV
	 * item number, name or barcode; sort navItemNo (the default) or name; dir asc (the default) or desc.
	 */
	@GetMapping("/catalogue")
	public ResponseEntity<?> catalogue(@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "20") int size,
			@RequestParam(name = "search", required = false) String search,
			@RequestParam(name = "categoryCode", required = false) String categoryCode,
			@RequestParam(name = "familyId", required = false) Long familyId,
			@RequestParam(name = "subFamilyId", required = false) Long subFamilyId,
			@RequestParam(name = "sectionCode", required = false) String sectionCode,
			@RequestParam(name = "sort", required = false) String sort,
			@RequestParam(name = "dir", required = false) String dir) {
		return answer(() -> {
			NavItemFilter filter = new NavItemFilter();
			filter.setSearch(search);
			filter.setCategoryCode(categoryCode);
			filter.setFamilyId(familyId);
			filter.setSubFamilyId(subFamilyId);
			filter.setSectionCode(sectionCode);
			filter.setSort(sort);
			filter.setDescending("desc".equalsIgnoreCase(dir == null ? "" : dir.trim()));
			Page<StoreOrderCatalogueLineDTO> lines = service.catalogue(filter, page, size);
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("content", lines.getContent());
			response.put("page", lines.getNumber());
			response.put("size", lines.getSize());
			response.put("totalElements", lines.getTotalElements());
			response.put("totalPages", lines.getTotalPages());
			return ResponseEntity.ok(response);
		});
	}

	/**
	 * GET /store-order/variants?navItemNo: the active variants with NAV's stock at this store, asked to the head office
	 * now (503 without it), and the draft's quantity of each.
	 */
	@GetMapping("/variants")
	public ResponseEntity<?> variants(@RequestParam(name = "navItemNo") String navItemNo) {
		return answer(() -> found(service.variants(navItemNo)));
	}

	/** GET /store-order/draft: the draft with its lines; 204 when there is none. */
	@GetMapping("/draft")
	public ResponseEntity<?> draft() {
		return answer(() -> orNoContent(service.draft()));
	}

	/** POST /store-order/draft/lines {itemId, quantity}: adds or replaces the item's line (0 removes it); the draft. */
	@PostMapping("/draft/lines")
	public ResponseEntity<?> putLine(@RequestBody(required = false) StoreOrderInputDTO body) {
		return answer(() -> orNoContent(service.putLine(body == null ? null : body.getItemId(),
				body == null ? null : body.getQuantity(), userName())));
	}

	/** DELETE /store-order/draft/lines/{itemId}: the draft without the item's line; 404 without a draft. */
	@DeleteMapping("/draft/lines/{itemId}")
	public ResponseEntity<?> removeLine(@PathVariable Long itemId) {
		return answer(() -> found(service.removeLine(itemId, userName())));
	}

	/** PUT /store-order/draft/note {note}: the draft's note; 404 without a draft. */
	@PutMapping("/draft/note")
	public ResponseEntity<?> note(@RequestBody(required = false) StoreOrderInputDTO body) {
		return answer(() -> found(service.setNote(body == null ? null : body.getNote(), userName())));
	}

	/** POST /store-order/{id}/send: the order SENT (the head office has it); 409 refused, 503 not answered. */
	@PostMapping("/{id}/send")
	public ResponseEntity<?> send(@PathVariable Long id) {
		return answer(() -> found(service.send(id, userName())));
	}

	/** POST /store-order/{id}/cancel: the draft CANCELLED; 409 for an order that is no longer a draft. */
	@PostMapping("/{id}/cancel")
	public ResponseEntity<?> cancel(@PathVariable Long id) {
		return answer(() -> found(service.cancel(id, userName())));
	}

	/**
	 * GET /store-order?status&amp;from&amp;to&amp;page&amp;size: {content, totalElements, totalPages, number, size}, newest
	 * first, without lines. status: DRAFT, SENT, POSTED, ERROR, CANCELLED, or none / ALL; from, to: yyyy-MM-dd, the
	 * creation day, both included.
	 */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam(required = false) String status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return answer(() -> ResponseEntity.ok(service.list(status, from, to, page, size)));
	}

	/** GET /store-order/{id}: the order with its lines. */
	@GetMapping("/{id}")
	public ResponseEntity<?> get(@PathVariable Long id) {
		return answer(() -> found(service.get(id)));
	}

	private String userName() {
		try {
			return currentUser.getCurrentUserName();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static ResponseEntity<?> found(Optional<?> value) {
		return value.<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(NOT_FOUND)));
	}

	private static ResponseEntity<?> orNoContent(Optional<StoreOrderViewDTO> draft) {
		return draft.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
	}

	private ResponseEntity<?> answer(Supplier<ResponseEntity<?>> call) {
		try {
			return call.get();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(error(e.getMessage()));
		} catch (StoreOrderService.HeadOfficeUnavailableException e) {
			log.warn("StoreOrderAPI: {}", e.getMessage());
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(error(e.getMessage()));
		} catch (RuntimeException e) {
			log.error("StoreOrderAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
