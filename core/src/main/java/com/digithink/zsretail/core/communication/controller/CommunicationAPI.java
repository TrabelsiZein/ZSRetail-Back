package com.digithink.zsretail.core.communication.controller;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.communication.CommunicationException;
import com.digithink.zsretail.core.communication.service.CommunicationAdmin;
import com.digithink.zsretail.core.communication.service.CommunicationAdmin.Filters;
import com.digithink.zsretail.core.communication.service.CommunicationAdmin.NewRuleForm;
import com.digithink.zsretail.core.communication.service.CommunicationAdmin.TemplateForm;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.security.CurrentUserProvider;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication step 4: the Communication page, where the messages are made and sent
 * (Installation.COMMUNICATION_SENDS_HERE; elsewhere no such endpoint: 404). Every read needs read:admin-communication,
 * every change write:admin-communication (403 otherwise). A change refused: 400 (404 for a row not found) with
 * {error, code, params}, the front's serverErrors.&lt;code&gt;.
 * <ul>
 * <li>GET /communication/messages?status=&amp;channel=&amp;dateFrom=&amp;dateTo=&amp;q=&amp;page=&amp;size=, GET
 * /communication/messages/{id} (with its event), POST /communication/messages/{id}/resend;</li>
 * <li>GET /communication/events?status=SKIPPED|ERROR&amp;dateFrom=&amp;dateTo=&amp;q=&amp;page=&amp;size=;</li>
 * <li>GET /communication/counters;</li>
 * <li>GET /communication/rules, GET /communication/rules/{id}, PUT /communication/rules/{id} {channel, templateCode,
 * lifetimeHours}, PUT /communication/rules/{id}/active {active}; step 5: POST /communication/rules {code, eventType,
 * channel, templateCode, lifetimeHours, active};</li>
 * <li>GET /communication/templates, GET /communication/templates/{id}, POST /communication/templates, PUT
 * /communication/templates/{id}, PUT /communication/templates/{id}/active {active}, POST
 * /communication/templates/preview {channel, body}, GET /communication/templates/fields.</li>
 * </ul>
 * No delete: a rule or a template is switched off.
 */
@RestController
@RequestMapping("communication")
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
@Log4j2
public class CommunicationAPI {

	public static final String READ = "read:admin-communication";
	public static final String WRITE = "write:admin-communication";

	@Autowired
	private CommunicationAdmin admin;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	// ─── Messages and events ─────────────────────────────────────────

	@GetMapping("/messages")
	public ResponseEntity<?> messages(@RequestParam(required = false) String status,
			@RequestParam(required = false) String channel, @RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String store) {
		return read(() -> admin.messages(Filters.of(status, channel, dateFrom, dateTo, q, store), page, size));
	}

	@GetMapping("/messages/{id}")
	public ResponseEntity<?> message(@PathVariable long id) {
		return read(() -> admin.message(id));
	}

	@PostMapping("/messages/{id}/resend")
	public ResponseEntity<?> resend(@PathVariable long id) {
		return write(() -> admin.resend(id, currentUserProvider.getCurrentUserName()));
	}

	@GetMapping("/events")
	public ResponseEntity<?> events(@RequestParam(required = false) String status,
			@RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String store) {
		return read(() -> admin.events(Filters.of(status, null, dateFrom, dateTo, q, store), page, size));
	}

	@GetMapping("/counters")
	public ResponseEntity<?> counters(@RequestParam(required = false) String store) {
		return read(() -> admin.counters(store));
	}

	/** Step 6: the stores of a head office for the store filter, [{code, name}]; [] on a store. */
	@GetMapping("/stores")
	public ResponseEntity<?> stores() {
		return read(admin::stores);
	}

	// ─── Rules ───────────────────────────────────────────────────────

	@GetMapping("/rules")
	public ResponseEntity<?> rules() {
		return read(admin::rules);
	}

	@GetMapping("/rules/{id}")
	public ResponseEntity<?> rule(@PathVariable long id) {
		return read(() -> admin.rule(id));
	}

	/** The rule's form: {channel, templateCode, lifetimeHours}. */
	public static final class RuleForm {
		public String channel;
		public String templateCode;
		public Integer lifetimeHours;
	}

	/** {active: true | false}. */
	public static final class ActiveForm {
		public Boolean active;
	}

	/** Step 5: a new rule {code, eventType, channel, templateCode, lifetimeHours, active}. */
	@PostMapping("/rules")
	public ResponseEntity<?> createRule(@RequestBody NewRuleForm form) {
		return write(() -> admin.createRule(form, currentUserProvider.getCurrentUserName()));
	}

	@PutMapping("/rules/{id}")
	public ResponseEntity<?> updateRule(@PathVariable long id, @RequestBody RuleForm form) {
		return write(() -> admin.updateRule(id, form.channel, form.templateCode, form.lifetimeHours,
				currentUserProvider.getCurrentUserName()));
	}

	@PutMapping("/rules/{id}/active")
	public ResponseEntity<?> ruleActive(@PathVariable long id, @RequestBody ActiveForm form) {
		return write(() -> admin.ruleActive(id, Boolean.TRUE.equals(form.active), currentUserProvider.getCurrentUserName()));
	}

	// ─── Templates ───────────────────────────────────────────────────

	@GetMapping("/templates")
	public ResponseEntity<?> templates() {
		return read(admin::templates);
	}

	@GetMapping("/templates/fields")
	public ResponseEntity<?> fields() {
		return read(admin::fields);
	}

	@GetMapping("/templates/{id}")
	public ResponseEntity<?> template(@PathVariable long id) {
		return read(() -> admin.template(id));
	}

	@PostMapping("/templates")
	public ResponseEntity<?> createTemplate(@RequestBody TemplateForm form) {
		return write(() -> admin.createTemplate(form, currentUserProvider.getCurrentUserName()));
	}

	@PutMapping("/templates/{id}")
	public ResponseEntity<?> updateTemplate(@PathVariable long id, @RequestBody TemplateForm form) {
		return write(() -> admin.updateTemplate(id, form, currentUserProvider.getCurrentUserName()));
	}

	@PutMapping("/templates/{id}/active")
	public ResponseEntity<?> templateActive(@PathVariable long id, @RequestBody ActiveForm form) {
		return write(() -> admin.templateActive(id, Boolean.TRUE.equals(form.active),
				currentUserProvider.getCurrentUserName()));
	}

	/** {channel, body}: the body filled with sample values (a read: the read right is enough). */
	@PostMapping("/templates/preview")
	public ResponseEntity<?> preview(@RequestBody Map<String, String> form) {
		return read(() -> admin.preview(form.get("channel"), form.get("body")));
	}

	// ─── Rights and answers ──────────────────────────────────────────

	private ResponseEntity<?> read(Supplier<Object> call) {
		return answer(READ, call);
	}

	private ResponseEntity<?> write(Supplier<Object> call) {
		return answer(WRITE, call);
	}

	private ResponseEntity<?> answer(String permission, Supplier<Object> call) {
		if (!currentUserProvider.currentUserHasPermission(permission)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Collections.singletonMap("error",
					"The Communication page needs the permission " + permission));
		}
		try {
			return ResponseEntity.ok(call.get());
		} catch (CommunicationException refused) {
			return ResponseEntity.status(refused.getStatus()).body(refused.body());
		} catch (RuntimeException e) {
			log.error("CommunicationAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Collections.singletonMap("error", e.getMessage()));
		}
	}
}
