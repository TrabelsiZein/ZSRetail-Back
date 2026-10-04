package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

import com.digithink.zsretail.config.ConditionalOnHeadOfficeWithoutErp;
import com.digithink.zsretail.headoffice.dto.PriceListDTO;
import com.digithink.zsretail.headoffice.dto.PriceListLineDTO;
import com.digithink.zsretail.headoffice.service.HoPriceListService;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 6.4: the selling price lists (JWT, like the other admin APIs). Head office without an ERP only:
 * elsewhere these URLs answer 404. Errors: 400 {"error"} for invalid data, 404 {"error":"Not found"}, 409 {"error"}
 * for a list used by a store or a code that exists. See docs/modules/head-office.md, "Price lists".
 */
@RestController
@RequestMapping("admin/headoffice/price-lists")
@ConditionalOnHeadOfficeWithoutErp
@Log4j2
public class HoPriceListAPI {

	private static final String NOT_FOUND = "Not found";

	private final HoPriceListService service;

	public HoPriceListAPI(HoPriceListService service) {
		this.service = service;
	}

	/** Step 7B: kind SELLING or SUPPLY filters; absent, every list. */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam(required = false) String kind) {
		return answer(() -> ResponseEntity.ok(service.findAll(kind)));
	}

	@GetMapping("/{id}")
	public ResponseEntity<?> get(@PathVariable Long id) {
		return answer(() -> service.findById(id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	/** Body {code, name, active}: 201 the list. */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody PriceListDTO body) {
		return answer(() -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(body)));
	}

	/** Body {name, active} (each when sent; the code cannot change). */
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody PriceListDTO body) {
		return answer(() -> service.update(id, body).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<?> delete(@PathVariable Long id) {
		return answer(() -> service.delete(id) ? ResponseEntity.noContent().build() : notFound());
	}

	/** A page {content, totalElements, totalPages, number, size} of lines, by item code. */
	@GetMapping("/{id}/lines")
	public ResponseEntity<?> lines(@PathVariable Long id, @RequestParam(required = false) String search,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return answer(() -> service.lines(id, search, page, size).<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElseGet(this::notFound));
	}

	/** Body [{itemCode, price}]: lines created or replaced, all or none; 200 the lines written. */
	@PutMapping("/{id}/lines")
	public ResponseEntity<?> putLines(@PathVariable Long id, @RequestBody List<PriceListLineDTO> body) {
		return answer(() -> service.putLines(id, body).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	@DeleteMapping("/{id}/lines/{lineId}")
	public ResponseEntity<?> deleteLine(@PathVariable Long id, @PathVariable Long lineId) {
		return answer(() -> service.deleteLine(id, lineId) ? ResponseEntity.noContent().build() : notFound());
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
			log.error("HoPriceListAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
