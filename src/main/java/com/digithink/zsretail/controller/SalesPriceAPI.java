package com.digithink.zsretail.controller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
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

import com.digithink.zsretail.holink.service.StoreCatalogueGuard;
import com.digithink.zsretail.model.SalesPrice;
import com.digithink.zsretail.service.SalesPriceService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("sales-price")
@Log4j2
public class SalesPriceAPI extends _BaseController<SalesPrice, Long, SalesPriceService> {

	@Autowired
	private SalesPriceService salesPriceService;

	/** Step 6: the rules of a store whose catalogue is the head office's; no bean on every other installation. */
	@Autowired(required = false)
	private ObjectProvider<StoreCatalogueGuard> catalogueGuard;

	/** Step 6: 409 on a store whose selling prices come from the head office; null otherwise. */
	private ResponseEntity<?> refused() {
		StoreCatalogueGuard guard = catalogueGuard == null ? null : catalogueGuard.getIfAvailable();
		return guard == null ? null
				: ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(guard.salesPriceWrite()));
	}

	/** The generic create; step 6: refused when selling prices come from the head office. */
	@Override
	@PostMapping
	public ResponseEntity<?> create(@RequestBody SalesPrice entity) {
		ResponseEntity<?> refusal = refused();
		return refusal != null ? refusal : super.create(entity);
	}

	/** The generic update; step 6: as create. */
	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody SalesPrice entity) {
		ResponseEntity<?> refusal = refused();
		return refusal != null ? refusal : super.update(id, entity);
	}

	/** The generic delete; step 6: as create. */
	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		ResponseEntity<?> refusal = refused();
		return refusal != null ? refusal : super.deleteById(id);
	}

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

