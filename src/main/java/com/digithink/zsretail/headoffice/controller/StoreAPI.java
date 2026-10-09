package com.digithink.zsretail.headoffice.controller;

import java.util.Map;
import java.util.Optional;

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

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.controller._BaseController;
import com.digithink.zsretail.headoffice.dto.StoreListItemDTO;
import com.digithink.zsretail.headoffice.dto.StoreWithKeyDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.StoreService;

import lombok.extern.log4j.Log4j2;

/**
 * Head office stores list (task 1.2). Exists only on a head office: on a store these URLs answer 404.
 * The list and the read by id add the computed status (task 1.5); create, update and delete apply the rules of
 * {@link StoreService}.
 * The API key appears only in the answers of create and regenerate-key.
 */
@RestController
@RequestMapping("admin/headoffice/stores")
@ConditionalOnHeadOffice
@Log4j2
public class StoreAPI extends _BaseController<Store, Long, StoreService> {

	/** Every store with its computed status and the age of its last contact (task 1.5). */
	@Override
	@GetMapping
	public ResponseEntity<?> getAll() {
		try {
			log.info("StoreAPI::getAll");
			return ResponseEntity.ok(service.findAllWithStatus());
		} catch (Exception e) {
			log.error("StoreAPI::getAll:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** One store with its computed status, like the list. */
	@Override
	@GetMapping("/{id}")
	public ResponseEntity<?> getById(@PathVariable Long id) {
		try {
			log.info("StoreAPI::getById::" + id);
			Optional<StoreListItemDTO> store = service.findByIdWithStatus(id);
			return store.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
		} catch (Exception e) {
			log.error("StoreAPI::getById:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** 201 {store, apiKey}. 400 when code or name is missing, 409 when the code exists. */
	@Override
	@PostMapping
	public ResponseEntity<?> create(@RequestBody Store entity) {
		try {
			log.info("StoreAPI::create");
			StoreWithKeyDTO created = service.create(entity);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("StoreAPI::create:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** Name, kind and active only. 400 when the code differs; 409 when another store has the ERP customer number. */
	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Store entity) {
		try {
			log.info("StoreAPI::update::" + id);
			Optional<Store> updated = service.update(id, entity);
			return updated.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("StoreAPI::update:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** 204, or 409 once the store has contacted the head office (deactivate it instead). */
	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		try {
			log.info("StoreAPI::deleteById::" + id);
			if (!service.findById(id).isPresent()) {
				return ResponseEntity.notFound().build();
			}
			service.deleteById(id);
			return ResponseEntity.noContent().build();
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("StoreAPI::deleteById:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Task 6.4: the store's selling price list. Body {"priceListId": 3}, or {"priceListId": null} for none (the base
	 * price). 200 the store; 400 for an unknown or inactive list; 404 for an unknown store.
	 */
	@PutMapping("/{id}/selling-price-list")
	public ResponseEntity<?> setSellingPriceList(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
		try {
			log.info("StoreAPI::setSellingPriceList::" + id);
			Object raw = body == null ? null : body.get("priceListId");
			Long priceListId;
			if (raw == null) {
				priceListId = null;
			} else if (raw instanceof Number) {
				priceListId = ((Number) raw).longValue();
			} else {
				return ResponseEntity.badRequest().body(createErrorResponse("priceListId must be a number or null."));
			}
			Optional<Store> updated = service.setSellingPriceList(id, priceListId);
			return updated.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("StoreAPI::setSellingPriceList:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Stock points (release 2.2): the store's point de stock. Body {"stockPointId": 2}, or {"stockPointId": null} for
	 * none. Every item and barcode is sent again to this store. 200 the store; 400 for an unknown or inactive point, a
	 * point without items, or a head office without stock points; 404 for an unknown store.
	 */
	@PutMapping("/{id}/stock-point")
	public ResponseEntity<?> setStockPoint(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
		try {
			log.info("StoreAPI::setStockPoint::" + id);
			Object raw = body == null ? null : body.get("stockPointId");
			if (raw != null && !(raw instanceof Number)) {
				return ResponseEntity.badRequest().body(createErrorResponse("stockPointId must be a number or null."));
			}
			Optional<Store> updated = service.setStockPoint(id, raw == null ? null : ((Number) raw).longValue());
			return updated.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("StoreAPI::setStockPoint:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Step 7B: the store's supply price list. Body {"priceListId": 4}, or {"priceListId": null} for none (the base
	 * supply price). 200 the store; 400 for an unknown, inactive or selling list; 404 for an unknown store.
	 */
	@PutMapping("/{id}/supply-price-list")
	public ResponseEntity<?> setSupplyPriceList(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
		try {
			log.info("StoreAPI::setSupplyPriceList::" + id);
			Object raw = body == null ? null : body.get("priceListId");
			if (raw != null && !(raw instanceof Number)) {
				return ResponseEntity.badRequest().body(createErrorResponse("priceListId must be a number or null."));
			}
			Optional<Store> updated = service.setSupplyPriceList(id, raw == null ? null : ((Number) raw).longValue());
			return updated.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("StoreAPI::setSupplyPriceList:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** 200 {store, apiKey}: the new key, shown once; the old key stops working. */
	@PostMapping("/{id}/regenerate-key")
	public ResponseEntity<?> regenerateKey(@PathVariable Long id) {
		try {
			log.info("StoreAPI::regenerateKey::" + id);
			Optional<StoreWithKeyDTO> regenerated = service.regenerateKey(id);
			return regenerated.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
		} catch (Exception e) {
			log.error("StoreAPI::regenerateKey:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** The generic search, except on the key hash. */
	@Override
	@GetMapping("/findByField")
	public ResponseEntity<?> findByField(@RequestParam String fieldName, @RequestParam String operation,
			@RequestParam Object value) {
		if ("apiKeyHash".equalsIgnoreCase(fieldName)) {
			return ResponseEntity.badRequest().body(createErrorResponse("Search on apiKeyHash is not allowed."));
		}
		return super.findByField(fieldName, operation, value);
	}
}
