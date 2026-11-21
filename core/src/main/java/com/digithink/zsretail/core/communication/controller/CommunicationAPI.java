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
import com.digithink.zsretail.core.communication.service.CommunicationCampaigns;
import com.digithink.zsretail.core.communication.service.CommunicationCampaigns.LaunchForm;
import com.digithink.zsretail.core.communication.service.CommunicationChannels;
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
 * <li>GET /communication/rules, PUT /communication/rules/{id} {channel, templateCode,
 * lifetimeHours}, PUT /communication/rules/{id}/active {active}; step 5: POST /communication/rules {code, eventType,
 * channel, templateCode, lifetimeHours, active};</li>
 * <li>GET /communication/templates, POST /communication/templates, PUT
 * /communication/templates/{id}, PUT /communication/templates/{id}/active {active}, POST
 * /communication/templates/preview {channel, body}, GET /communication/templates/fields;</li>
 * <li>step 7: GET /communication/campaigns?page=&amp;size=, GET /communication/campaigns/names, GET
 * /communication/campaigns/templates, POST /communication/campaigns/audience {templateCode, channel} (read), GET
 * /communication/campaigns/{id}, POST /communication/campaigns {name, templateCode, channel, lifetimeHours,
 * confirmSameDay} and POST /communication/campaigns/{id}/cancel (step 8a: write:admin-communication-campaigns); the
 * messages and the counters take campaign=&lt;id&gt;;</li>
 * <li>step 8a: GET /communication/channels (read), POST /communication/channels/{channel}/test {address} (write).</li>
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
	/** Step 8a: launching and cancelling a campaign. */
	public static final String LAUNCH = "write:admin-communication-campaigns";

	@Autowired
	private CommunicationAdmin admin;

	@Autowired
	private CommunicationCampaigns campaigns;

	@Autowired
	private CommunicationChannels channels;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	// ─── Messages and events ─────────────────────────────────────────

	@GetMapping("/messages")
	public ResponseEntity<?> messages(@RequestParam(required = false) String status,
			@RequestParam(required = false) String channel, @RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String store, @RequestParam(required = false) Long campaign) {
		return read(() -> admin.messages(Filters.of(status, channel, dateFrom, dateTo, q, store, campaign), page, size));
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
	public ResponseEntity<?> counters(@RequestParam(required = false) String store,
			@RequestParam(required = false) Long campaign) {
		return read(() -> admin.counters(store, campaign));
	}

	// ─── Campaigns (step 7) ──────────────────────────────────────────

	@GetMapping("/campaigns")
	public ResponseEntity<?> campaigns(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return read(() -> campaigns.list(page, size));
	}

	/** The latest campaigns {id, code, name}, for the Messages tab's filter. */
	@GetMapping("/campaigns/names")
	public ResponseEntity<?> campaignNames() {
		return read(campaigns::names);
	}

	/** Every template with what a campaign may do with it (usable, its sale's fields, its channel on). */
	@GetMapping("/campaigns/templates")
	public ResponseEntity<?> campaignTemplates() {
		return read(campaigns::templates);
	}

	/** {templateCode, channel}: who would receive it, who is left out and why, the preview with a real member. */
	@PostMapping("/campaigns/audience")
	public ResponseEntity<?> audience(@RequestBody Map<String, String> form) {
		return read(() -> campaigns.audience(form.get("templateCode"), form.get("channel")));
	}

	@GetMapping("/campaigns/{id}")
	public ResponseEntity<?> campaign(@PathVariable long id) {
		return read(() -> campaigns.campaign(id));
	}

	/**
	 * {name, templateCode, channel, lifetimeHours, confirmSameDay}: launched (its messages made in the background). Step
	 * 8a: its own right, write:admin-communication-campaigns.
	 */
	@PostMapping("/campaigns")
	public ResponseEntity<?> launch(@RequestBody LaunchForm form) {
		return answer(LAUNCH, () -> campaigns.launch(form, currentUserProvider.getCurrentUserName()));
	}

	@PostMapping("/campaigns/{id}/cancel")
	public ResponseEntity<?> cancel(@PathVariable long id) {
		return answer(LAUNCH, () -> campaigns.cancel(id, currentUserProvider.getCurrentUserName()));
	}

	// ─── Channels (step 8a) ──────────────────────────────────────────

	/** The Canaux tab: each channel, its provider's settings (never a password), its test recipient, its last result. */
	@GetMapping("/channels")
	public ResponseEntity<?> channels() {
		return read(channels::view);
	}

	/** {address}: a fixed short text sent now through the channel's provider (write right). */
	@PostMapping("/channels/{channel}/test")
	public ResponseEntity<?> testSend(@PathVariable String channel, @RequestBody Map<String, String> form) {
		return write(() -> channels.testSend(channel, form.get("address"), currentUserProvider.getCurrentUserName(),
				form.get("templateCode")));
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
			// Step 8e: with its code, so that the page says it in the screen's language
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new CommunicationException(HttpStatus.FORBIDDEN.value(),
					"COMM_FORBIDDEN", "The Communication page needs the permission " + permission, "permission", permission)
					.body());
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
