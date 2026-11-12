package com.digithink.zsretail.store.controller;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
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

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.CommonTicketIssuers;
import com.digithink.zsretail.store.service.AcceptedTicketService;
import com.digithink.zsretail.store.service.PaymentMethodService;
import com.digithink.zsretail.store.service.ScannedTicketService;
import com.digithink.zsretail.store.service.PaymentMethodService.Refusal;

import lombok.extern.log4j.Log4j2;

/**
 * 3.2.0, step 3: the accepted tickets of a scanned-tickets payment method, under /payment-method/{methodId}/tickets:
 * list, add, edit, delete, "add the common issuers" (?kind=MEAL|GIFT) and "test a barcode" (the reading, nothing
 * stored; ?date= for another day than today). The page's rights: reading and testing read:admin-payment-methods (or the
 * write right), the changes write:admin-payment-methods (403). A refusal is 400 with {error, reason}; a method or line
 * not found, or a line of another method, 404. GET /payment-method/ticket-counts: the number of lines per method (the
 * list of the methods, any logged user).
 */
@RestController
@RequestMapping("payment-method")
@Log4j2
public class AcceptedTicketAPI {

	public static final String READ_PERMISSION = "read:admin-payment-methods";

	@Autowired
	private AcceptedTicketService service;

	@Autowired
	private PaymentMethodService methods;

	@Autowired
	private ScannedTicketService scannedTickets;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@GetMapping("/ticket-counts")
	public ResponseEntity<?> counts() {
		return ResponseEntity.ok(service.countPerMethod());
	}

	@GetMapping("/{methodId}/tickets")
	public ResponseEntity<?> list(@PathVariable Long methodId) {
		if (!canRead()) {
			return forbidden();
		}
		Optional<PaymentMethod> method = methods.findById(methodId);
		return method.isPresent() ? ResponseEntity.ok(service.lines(method.get())) : ResponseEntity.notFound().build();
	}

	@GetMapping("/{methodId}/tickets/test")
	public ResponseEntity<?> test(@PathVariable Long methodId, @RequestParam String barcode,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		if (!canRead()) {
			return forbidden();
		}
		Optional<PaymentMethod> method = methods.findById(methodId);
		if (!method.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		// step 4: the sale that holds the barcode given beside (usedSaleNumber...)
		return ResponseEntity.ok(scannedTickets.test(method.get(), barcode, date != null ? date : LocalDate.now()));
	}

	/**
	 * 3.2.0, step 4: the till's check of a scanned ticket for the method, today: the reading and, when a sale holds the
	 * barcode, ALREADY_USED with its sale number, date, session and cashier. Reserves nothing (the sale decides). Any
	 * logged user, as a sale.
	 */
	@GetMapping("/{methodId}/tickets/check")
	public ResponseEntity<?> check(@PathVariable Long methodId, @RequestParam String barcode) {
		Optional<PaymentMethod> method = methods.findById(methodId);
		if (!method.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(scannedTickets.check(method.get(), barcode));
	}

	@PostMapping("/{methodId}/tickets")
	public ResponseEntity<?> add(@PathVariable Long methodId, @RequestBody AcceptedTicket line) throws Exception {
		if (!canWrite()) {
			return forbidden();
		}
		Optional<PaymentMethod> method = methods.findById(methodId);
		if (!method.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		try {
			return ResponseEntity.status(HttpStatus.CREATED).body(service.add(method.get(), line));
		} catch (Refusal refusal) {
			return refused(HttpStatus.BAD_REQUEST, refusal);
		}
	}

	@PutMapping("/{methodId}/tickets/{id}")
	public ResponseEntity<?> edit(@PathVariable Long methodId, @PathVariable Long id, @RequestBody AcceptedTicket line)
			throws Exception {
		if (!canWrite()) {
			return forbidden();
		}
		Optional<AcceptedTicket> stored = lineOf(methodId, id);
		if (!stored.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		try {
			return ResponseEntity.ok(service.edit(stored.get(), line));
		} catch (Refusal refusal) {
			return refused(HttpStatus.BAD_REQUEST, refusal);
		}
	}

	@DeleteMapping("/{methodId}/tickets/{id}")
	public ResponseEntity<?> delete(@PathVariable Long methodId, @PathVariable Long id) {
		if (!canWrite()) {
			return forbidden();
		}
		Optional<AcceptedTicket> stored = lineOf(methodId, id);
		if (!stored.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		Optional<Refusal> refusal = service.deletionRefusal(stored.get());
		if (refusal.isPresent()) {
			return refused(HttpStatus.CONFLICT, refusal.get());
		}
		service.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{methodId}/tickets/common")
	public ResponseEntity<?> addCommon(@PathVariable Long methodId, @RequestParam CommonTicketIssuers.Kind kind)
			throws Exception {
		if (!canWrite()) {
			return forbidden();
		}
		Optional<PaymentMethod> method = methods.findById(methodId);
		if (!method.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		try {
			return ResponseEntity.ok(service.addCommon(method.get(), kind));
		} catch (Refusal refusal) {
			return refused(HttpStatus.BAD_REQUEST, refusal);
		}
	}

	private Optional<AcceptedTicket> lineOf(Long methodId, Long id) {
		return service.findById(id).filter(line -> methodId.equals(line.getPaymentMethodId()));
	}

	private boolean canRead() {
		return currentUserProvider.currentUserHasPermission(READ_PERMISSION) || canWrite();
	}

	private boolean canWrite() {
		return currentUserProvider.currentUserHasPermission(PaymentMethodService.WRITE_PERMISSION);
	}

	private static ResponseEntity<?> forbidden() {
		return refused(HttpStatus.FORBIDDEN,
				new Refusal("FORBIDDEN", "The accepted tickets need the rights of the Payment Methods page."));
	}

	private static ResponseEntity<?> refused(HttpStatus status, Refusal refusal) {
		return PaymentMethodAPI.refused(status, refusal);
	}
}
