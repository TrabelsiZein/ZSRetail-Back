package com.digithink.zsretail.store.controller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
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

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.service.PaymentMethodService;
import com.digithink.zsretail.store.service.PaymentMethodService.Refusal;

/**
 * The payment methods: the generic CRUD. Reading stays open to a logged user (the till, the closing, the payment change
 * read the list). 3.2.0, step 2: POST, PUT and DELETE need the right write:admin-payment-methods (403), and go through
 * PaymentMethodService's rules: a refused method answers 400 (a refused deletion 409) with {error, reason}, before
 * anything is written.
 */
@RestController
@RequestMapping("payment-method")
public class PaymentMethodAPI extends _BaseController<PaymentMethod, Long, PaymentMethodService> {

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@Override
	@PostMapping
	public ResponseEntity<?> create(@RequestBody PaymentMethod entity) {
		if (!canWrite()) {
			return forbidden();
		}
		try {
			service.prepareCreate(entity);
		} catch (Refusal refusal) {
			return refused(HttpStatus.BAD_REQUEST, refusal);
		}
		return super.create(entity);
	}

	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody PaymentMethod entity) {
		if (!canWrite()) {
			return forbidden();
		}
		Optional<PaymentMethod> stored = service.findById(id);
		if (!stored.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		try {
			service.prepareUpdate(stored.get(), entity);
		} catch (Refusal refusal) {
			return refused(HttpStatus.BAD_REQUEST, refusal);
		}
		return super.update(id, stored.get());
	}

	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		if (!canWrite()) {
			return forbidden();
		}
		Optional<PaymentMethod> stored = service.findById(id);
		if (!stored.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		Optional<Refusal> refusal = service.deletionRefusal(stored.get());
		if (refusal.isPresent()) {
			return refused(HttpStatus.CONFLICT, refusal.get());
		}
		return super.deleteById(id);
	}

	/** Step 3: the behaviour of this method cannot change (the dialog greys its choice); a logged user. */
	@GetMapping("/{id}/behaviour-locked")
	public ResponseEntity<?> behaviourLocked(@PathVariable Long id) {
		Optional<PaymentMethod> stored = service.findById(id);
		if (!stored.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(Collections.singletonMap("locked", service.behaviourLocked(stored.get())));
	}

	/** Step 3b: the General setup rows shown on the method's card (empty for most methods); a logged user. */
	@GetMapping("/{id}/settings")
	public ResponseEntity<?> settings(@PathVariable Long id) {
		Optional<PaymentMethod> stored = service.findById(id);
		if (!stored.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(service.settingsOf(stored.get()));
	}

	/**
	 * Step 3b: the card's settings saved, {code: value}: the right write:admin-payment-methods (not General setup's), the
	 * keys of the method's type only, the rules of General setup (PaymentMethodService.saveSettings); 400 when refused.
	 */
	@PutMapping("/{id}/settings")
	public ResponseEntity<?> saveSettings(@PathVariable Long id, @RequestBody Map<String, String> values)
			throws Exception {
		if (!canWrite()) {
			return forbidden();
		}
		Optional<PaymentMethod> stored = service.findById(id);
		if (!stored.isPresent()) {
			return ResponseEntity.notFound().build();
		}
		try {
			service.saveSettings(stored.get(), values);
		} catch (Refusal refusal) {
			return refused(HttpStatus.BAD_REQUEST, refusal);
		} catch (IllegalStateException readOnlyOrChecked) {
			return refused(HttpStatus.BAD_REQUEST, new Refusal("SETTING_INVALID", readOnlyOrChecked.getMessage()));
		}
		return ResponseEntity.ok(service.settingsOf(stored.get()));
	}

	private boolean canWrite() {
		return currentUserProvider.currentUserHasPermission(PaymentMethodService.WRITE_PERMISSION);
	}

	private static ResponseEntity<?> forbidden() {
		return refused(HttpStatus.FORBIDDEN, new Refusal("FORBIDDEN",
				"Changing the payment methods needs the right " + PaymentMethodService.WRITE_PERMISSION + "."));
	}

	static ResponseEntity<?> refused(HttpStatus status, Refusal refusal) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", refusal.getMessage());
		body.put("reason", refusal.getReason());
		if (!refusal.getDetails().isEmpty()) {
			body.put("details", refusal.getDetails());
		}
		return ResponseEntity.status(status).body(body);
	}
}
