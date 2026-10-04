package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeWithoutErp;
import com.digithink.zsretail.headoffice.dto.SupplyInvoiceDTO;
import com.digithink.zsretail.headoffice.service.HoSupplyInvoiceService;
import com.digithink.zsretail.security.CurrentUserProvider;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, step 7B: the supply invoices to the stores (JWT, like the other admin APIs). Head office without an
 * ERP only: elsewhere these URLs answer 404. Errors {"error"}: 400 invalid data, 404 unknown invoice, 409 a store whose
 * deliveries are not invoiced, a BL that cannot be invoiced, items without a supply price. See
 * docs/modules/head-office.md, "Supply invoices".
 */
@RestController
@RequestMapping("admin/headoffice/supply-invoices")
@ConditionalOnHeadOfficeWithoutErp
@Log4j2
public class HoSupplyInvoiceAPI {

	private static final String NOT_FOUND = "Not found";

	private final HoSupplyInvoiceService service;
	private final CurrentUserProvider currentUser;

	public HoSupplyInvoiceAPI(HoSupplyInvoiceService service, CurrentUserProvider currentUser) {
		this.service = service;
		this.currentUser = currentUser;
	}

	/** {content, totalElements, totalPages, number, size}, newest first. */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam(required = false) Long storeId,
			@RequestParam(required = false) Boolean paid, @RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return answer(() -> ResponseEntity.ok(service.list(storeId, paid, dateFrom, dateTo, page, size)));
	}

	@GetMapping("/{id}")
	public ResponseEntity<?> get(@PathVariable Long id) {
		return answer(() -> service.get(id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	/** The received BLs of a store not invoiced yet, with the reason an automatic invoice failed. */
	@GetMapping("/to-invoice")
	public ResponseEntity<?> toInvoice(@RequestParam(required = false) Long storeId) {
		return answer(() -> ResponseEntity.ok(service.toInvoice(storeId)));
	}

	/** Body {storeId, deliveryIds, invoiceDate}: the invoice it would be, with missingPrices; writes nothing. */
	@PostMapping("/preview")
	public ResponseEntity<?> preview(@RequestBody SupplyInvoiceDTO body) {
		return answer(() -> ResponseEntity.ok(service.preview(body)));
	}

	/** Body {storeId, deliveryIds, invoiceDate, note}: 201 the invoice; its BLs become INVOICED. */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody SupplyInvoiceDTO body) {
		return answer(() -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(body, userName())));
	}

	/** Body {paid: true|false, paidDate: "yyyy-MM-dd" (today when absent), note}. */
	@PatchMapping("/{id}/paid")
	public ResponseEntity<?> setPaid(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
		Object paid = body == null ? null : body.get("paid");
		if (paid != null && !(paid instanceof Boolean)) {
			return ResponseEntity.badRequest().body(error("paid must be true or false."));
		}
		return answer(() -> service.setPaid(id, (Boolean) paid, text(body, "paidDate"), text(body, "note"))
				.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	/** What each store owes: [{storeId, storeCode, storeName, invoiceCount, total, paid, unpaid, unpaidCount}]. */
	@GetMapping("/balances")
	public ResponseEntity<?> balances() {
		return answer(() -> ResponseEntity.ok(service.balances()));
	}

	private static String text(Map<String, Object> body, String key) {
		Object value = body == null ? null : body.get(key);
		return value == null ? null : String.valueOf(value);
	}

	private String userName() {
		try {
			return currentUser.getCurrentUserName();
		} catch (RuntimeException e) {
			return null;
		}
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
			log.error("HoSupplyInvoiceAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
