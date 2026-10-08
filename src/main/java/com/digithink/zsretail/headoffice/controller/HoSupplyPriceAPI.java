package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwnSupply;
import com.digithink.zsretail.headoffice.dto.SupplyPriceDTO;
import com.digithink.zsretail.headoffice.service.HoSupplyPriceService;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, step 7B: the base supply prices (JWT, like the other admin APIs). Head office without an ERP only:
 * elsewhere these URLs answer 404. Errors {"error"}: 400 for invalid data. The supply price lists are the price lists of
 * kind SUPPLY (/admin/headoffice/price-lists?kind=SUPPLY). See docs/modules/head-office.md, "Supply prices".
 */
@RestController
@RequestMapping("admin/headoffice/supply-prices")
@ConditionalOnHeadOfficeOwnSupply
@Log4j2
public class HoSupplyPriceAPI {

	private final HoSupplyPriceService service;

	public HoSupplyPriceAPI(HoSupplyPriceService service) {
		this.service = service;
	}

	/** {content: [{itemId, itemCode, itemName, sellingPrice, supplyPrice}], totalElements, totalPages, number, size}. */
	@GetMapping
	public ResponseEntity<?> page(@RequestParam(required = false) String search,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return answer(() -> ResponseEntity.ok(service.page(search, page, size)));
	}

	/** Body [{itemCode, supplyPrice}] (supplyPrice null deletes); all or none; 200 the lines written. */
	@PutMapping
	public ResponseEntity<?> put(@RequestBody List<SupplyPriceDTO> body) {
		return answer(() -> ResponseEntity.ok(service.putPrices(body)));
	}

	private ResponseEntity<?> answer(Supplier<ResponseEntity<?>> call) {
		try {
			return call.get();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		} catch (RuntimeException e) {
			log.error("HoSupplyPriceAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
