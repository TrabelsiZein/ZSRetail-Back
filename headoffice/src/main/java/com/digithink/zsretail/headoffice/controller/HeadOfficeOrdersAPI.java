package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.SalesCopyResultDTO;
import com.digithink.zsretail.core.link.dto.StoreOrderDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.HoStoreOrderService;

import lombok.extern.log4j.Log4j2;

/**
 * 3.1.1: a store's orders, under the /ho/** chain (store key, then license), on a head office that takes them
 * (headoffice.storeorders.post set; elsewhere 404). The store is the principal set by StoreApiKeyFilter: the location
 * is its code, never a parameter. See docs/modules/store-orders.md.
 */
@RestController
@RequestMapping("ho/orders")
@ConditionalOnInstallation(Installation.HEAD_OFFICE_STORE_ORDERS)
@Log4j2
public class HeadOfficeOrdersAPI {

	private final HoStoreOrderService service;

	public HeadOfficeOrdersAPI(HoStoreOrderService service) {
		this.service = service;
	}

	/**
	 * POST /ho/orders: one order of the store; {documentNumber, accepted, message}. A number already here for this store
	 * is accepted again (never a second order); a refusal says why, nothing saved.
	 */
	@PostMapping
	public SalesCopyResultDTO receive(@AuthenticationPrincipal Store store, @RequestBody StoreOrderDTO order) {
		return service.receive(store, order);
	}

	/**
	 * GET /ho/orders/variant-stock?navItemNo=: [{itemNo, variantCode, quantity, details, sizeOrder}], the ERP's stock of
	 * every variant for the store, read now. 400 without a number, 409 {"error"} for a store without an ERP customer
	 * number, 503 {"error"} when the ERP does not answer it.
	 */
	@GetMapping("/variant-stock")
	public ResponseEntity<?> variantStock(@AuthenticationPrincipal Store store,
			@RequestParam(name = "navItemNo", required = false) String navItemNo) {
		try {
			return ResponseEntity.ok(service.variantStock(store, navItemNo));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
		} catch (HoStoreOrderService.StoreRefusedException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Collections.singletonMap("error", e.getMessage()));
		} catch (HoStoreOrderService.ErpUnavailableException e) {
			log.warn("Store orders, variant stock: {}", e.getMessage());
			return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
					.body(Collections.singletonMap("error", e.getMessage()));
		}
	}
}
