package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeStandalone;
import com.digithink.zsretail.headoffice.service.HoNetworkStockService;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 7A.5: the stock of every store next to the head office's own stock (JWT, like the other admin
 * APIs). Head office without an ERP only: elsewhere these URLs answer 404. Errors {"error"}: 400 for an unknown store or a
 * page that cannot be read. See docs/modules/head-office.md, "Stock of the stores".
 */
@RestController
@RequestMapping("admin/headoffice/stock")
@ConditionalOnHeadOfficeStandalone
@Log4j2
public class HoNetworkStockAPI {

	private final HoNetworkStockService service;

	public HoNetworkStockAPI(HoNetworkStockService service) {
		this.service = service;
	}

	/** The head office items with their stock and each store's: {stores, content, totalElements, ...}. */
	@GetMapping
	public ResponseEntity<?> page(@RequestParam(required = false) Long storeId,
			@RequestParam(required = false) String search, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return answer(() -> ResponseEntity.ok(service.page(storeId, search, page, size)));
	}

	/** The stores' own items (not from the head office) with their stock. */
	@GetMapping("/own")
	public ResponseEntity<?> own(@RequestParam(required = false) Long storeId,
			@RequestParam(required = false) String search, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return answer(() -> ResponseEntity.ok(service.ownItems(storeId, search, page, size)));
	}

	private ResponseEntity<?> answer(Supplier<ResponseEntity<?>> call) {
		try {
			return call.get();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		} catch (RuntimeException e) {
			log.error("HoNetworkStockAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
