package com.digithink.zsretail.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.model.SalesPrice;
import com.digithink.zsretail.service.SalesPriceService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("sales-price")
@Log4j2
public class SalesPriceAPI extends _BaseController<SalesPrice, Long, SalesPriceService> {

	@Autowired
	private SalesPriceService salesPriceService;

	/**
	 * Get paginated sales prices with optional search
	 * GET /sales-price/paginated?page=0&size=20&search=term
	 */
	@GetMapping("/paginated")
	public ResponseEntity<?> getAllPaginated(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String search) {
		try {
			log.info("SalesPriceAPI::getAllPaginated::page={}, size={}, search={}", page, size, search);
			Page<SalesPrice> salesPrices = salesPriceService.findAllPaginated(page, size, search);
			return ResponseEntity.ok(salesPrices);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("SalesPriceAPI::getAllPaginated:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}
}

