package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeErpCatalogue;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.headoffice.dto.StockPointDTO;
import com.digithink.zsretail.headoffice.service.HoStockPointService;

import lombok.extern.log4j.Log4j2;

/**
 * Stock points, step 1: the points de stock (JWT, like the other admin APIs). Head office whose catalogue comes from
 * the ERP with erp.navpospages.enabled=true only: elsewhere these URLs answer 404. Errors: 400 {"error"} for invalid
 * data, 404 {"error":"Not found"}, 409 {"error"} for a code that exists, a point used by a store, or a point with items
 * deleted. See docs/modules/head-office.md, "Stock points".
 */
@RestController
@RequestMapping("admin/headoffice/stock-points")
@ConditionalOnHeadOfficeErpCatalogue
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
@Log4j2
public class HoStockPointAPI {

	private static final String NOT_FOUND = "Not found";

	private final HoStockPointService service;

	public HoStockPointAPI(HoStockPointService service) {
		this.service = service;
	}

	/** Every point in list order. */
	@GetMapping
	public ResponseEntity<?> list() {
		return answer(() -> ResponseEntity.ok(service.findAll()));
	}

	@GetMapping("/{id}")
	public ResponseEntity<?> get(@PathVariable Long id) {
		return answer(() -> service.findById(id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	/** Body {code, name, active}: 201 the point, last in the list. */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody StockPointDTO body) {
		return answer(() -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(body)));
	}

	/** Body {name, active} (each when sent; the code cannot change). */
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody StockPointDTO body) {
		return answer(() -> service.update(id, body).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<?> delete(@PathVariable Long id) {
		return answer(() -> service.delete(id) ? ResponseEntity.noContent().build() : notFound());
	}

	/** Body [id, id, ...]: every point, each once, in the new order; 200 the list in that order. */
	@PutMapping("/order")
	public ResponseEntity<?> reorder(@RequestBody List<Long> body) {
		return answer(() -> ResponseEntity.ok(service.reorder(body)));
	}

	private ResponseEntity<?> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(NOT_FOUND));
	}

	private ResponseEntity<?> answer(Supplier<ResponseEntity<?>> call) {
		try {
			return call.get();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(error(e.getMessage()));
		} catch (RuntimeException e) {
			log.error("HoStockPointAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
