package com.digithink.zsretail.core.convention.controller;

import java.util.Collections;
import java.util.function.Supplier;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.convention.ConventionException;
import com.digithink.zsretail.core.convention.service.ConventionCards;
import com.digithink.zsretail.core.convention.service.ConventionCards.CardForm;
import com.digithink.zsretail.core.convention.service.ConventionService;
import com.digithink.zsretail.core.convention.service.ConventionService.Form;
import com.digithink.zsretail.core.security.CurrentUserProvider;

import lombok.extern.log4j.Log4j2;

/**
 * 3.6.0, conventions step 1: the Conventions pages' calls, a store and a head office alike, only with
 * convention.enabled=true (Installation.CONVENTION; off: no bean, 404). Hand-written, not _BaseController: its free PUT
 * and its DELETE would skip the rules and the events; every change goes through {@link ConventionService} (part B3) or
 * {@link ConventionCards} (part B4).
 * <ul>
 * <li>GET /conventions?state=ACTIVE|SUSPENDED|EXPIRED|UPCOMING|all&amp;q=&amp;page=&amp;size=, GET /conventions/{id},
 * GET /conventions/{id}/cards?status=ACTIVE|BLOCKED|all&amp;q=&amp;exported=&amp;page=&amp;size=:
 * read:admin-conventions;</li>
 * <li>POST /conventions, PUT /conventions/{id} (the whole form), PUT /conventions/{id}/status {status}, DELETE
 * /conventions/{id} (only without a card); POST /conventions/{id}/cards/generate {count}, POST /conventions/{id}/cards
 * {number, holderName, holderPhone, holderEmployeeNo}, PUT /conventions/{id}/cards/status {ids, status}, PUT
 * /conventions/{id}/cards/{cardId}/holder {holderName, holderPhone, holderEmployeeNo}: write:admin-conventions.</li>
 * </ul>
 * Without the right: 403 CONVENTION_FORBIDDEN. A change refused: 400 {error, code, params} (the front's
 * serverErrors.&lt;code&gt;), 404 CONVENTION_NOT_FOUND / CONVENTION_CARD_NOT_FOUND, 409 CONVENTION_OWNED_BY_HEAD_OFFICE on
 * a store whose promotions are the head office's (decision 2), 409 CONVENTION_CARDS_BUSY (a number taken during a
 * generation: try again). docs/modules/conventions.md.
 */
@RestController
@RequestMapping("conventions")
@ConditionalOnInstallation(Installation.CONVENTION)
@Log4j2
public class ConventionAPI {

	/** Decision 11: the same keys on a store and a head office (no admin-headoffice- twin). */
	public static final String READ = "read:admin-conventions";
	public static final String WRITE = "write:admin-conventions";

	@Autowired
	private ConventionService service;

	@Autowired
	private ConventionCards cards;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	// ─── The conventions (part B3) ───────────────────────────────────

	@GetMapping
	public ResponseEntity<?> list(@RequestParam(required = false) String state,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return answer(READ, () -> service.list(state, q, page, size));
	}

	@GetMapping("/{id}")
	public ResponseEntity<?> get(@PathVariable long id) {
		return answer(READ, () -> service.get(id));
	}

	@PostMapping
	public ResponseEntity<?> create(@RequestBody Form form) {
		return answer(WRITE, () -> service.create(form, user()));
	}

	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable long id, @RequestBody Form form) {
		return answer(WRITE, () -> service.update(id, form, user()));
	}

	/** {status: SUSPENDED | ACTIVE}. */
	public static final class StatusForm {
		public String status;
	}

	@PutMapping("/{id}/status")
	public ResponseEntity<?> status(@PathVariable long id, @RequestBody StatusForm form) {
		return answer(WRITE, () -> service.setStatus(id, form == null ? null : form.status, user()));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<?> delete(@PathVariable long id) {
		return answer(WRITE, () -> service.delete(id));
	}

	// ─── The cards (part B4) ─────────────────────────────────────────

	@GetMapping("/{id}/cards")
	public ResponseEntity<?> cards(@PathVariable long id, @RequestParam(required = false) String status,
			@RequestParam(required = false) String q, @RequestParam(required = false) Boolean exported,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return answer(READ, () -> cards.list(id, status, q, exported, page, size));
	}

	/** {count}: 1 to 5,000 cards. */
	public static final class GenerateForm {
		public Integer count;
	}

	@PostMapping("/{id}/cards/generate")
	public ResponseEntity<?> generate(@PathVariable long id, @RequestBody GenerateForm form) {
		return answer(WRITE, () -> cards.generate(id, form == null ? null : form.count, user()));
	}

	@PostMapping("/{id}/cards")
	public ResponseEntity<?> addCard(@PathVariable long id, @RequestBody CardForm form) {
		return answer(WRITE, () -> cards.add(id, form, user()));
	}

	@PutMapping("/{id}/cards/status")
	public ResponseEntity<?> cardsStatus(@PathVariable long id, @RequestBody ConventionCards.StatusForm form) {
		return answer(WRITE, () -> cards.setStatus(id, form, user()));
	}

	@PutMapping("/{id}/cards/{cardId}/holder")
	public ResponseEntity<?> holder(@PathVariable long id, @PathVariable long cardId, @RequestBody CardForm form) {
		return answer(WRITE, () -> cards.holder(id, cardId, form, user()));
	}

	// ─── Rights and answers ──────────────────────────────────────────

	private String user() {
		return currentUserProvider.getCurrentUserName();
	}

	private ResponseEntity<?> answer(String permission, Supplier<Object> call) {
		if (!currentUserProvider.currentUserHasPermission(permission)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ConventionException(HttpStatus.FORBIDDEN.value(),
					"CONVENTION_FORBIDDEN", "The Conventions page needs the permission " + permission, "permission",
					permission).body());
		}
		try {
			return ResponseEntity.ok(call.get());
		} catch (ConventionException refused) {
			return ResponseEntity.status(refused.getStatus()).body(refused.body());
		} catch (RuntimeException e) {
			log.error("ConventionAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Collections.singletonMap("error", e.getMessage()));
		}
	}
}
