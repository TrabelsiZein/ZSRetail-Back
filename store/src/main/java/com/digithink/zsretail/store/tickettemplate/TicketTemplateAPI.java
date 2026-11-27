package com.digithink.zsretail.store.tickettemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.ZZDataInitializer;
import com.digithink.zsretail.core.tickettemplate.TicketDocumentType;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateException;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;
import com.digithink.zsretail.store.controller.WarrantyAPI;

/**
 * 3.5.0, ticket templates step 2 (docs/modules/ticket-templates.md): the page "Modèles de tickets" and the till's print.
 * <ul>
 * <li>GET /ticket-templates: any logged user (the till prints with the template): the documents, their catalogue,
 * their saved template (null: the default prints) and their default, the widths, the sizes, the linked settings.</li>
 * <li>PUT /ticket-templates/{type}: write:admin-ticket-templates; the template checked and saved, its normalised form.</li>
 * <li>DELETE /ticket-templates/{type}: write:admin-ticket-templates; "Réinitialiser", the row deleted (204, also when
 * there was none).</li>
 * </ul>
 * A refusal answers {"error", "code", "params"} (400, 403, 404), the front shows serverErrors.&lt;code&gt;. Logged users
 * only (not in SecurityConfig's permitAll). A store's: a head office has no page before step 5.
 * <p>
 * Step 3: the warranty ticket's template follows warranty.enabled. Off: GET leaves the document WARRANTY out of the
 * list (a saved row stays in the table, listed again once the line is back on); PUT and DELETE of WARRANTY answer 404
 * {"error"} as the /warranty endpoints (WarrantyAPI.OFF_MESSAGE).
 */
@RestController
@RequestMapping("ticket-templates")
public class TicketTemplateAPI {

	private final TicketTemplateService service;
	private final CurrentUserProvider currentUserProvider;
	private final ApplicationModeService applicationModeService;

	public TicketTemplateAPI(TicketTemplateService service, CurrentUserProvider currentUserProvider,
			ApplicationModeService applicationModeService) {
		this.service = service;
		this.currentUserProvider = currentUserProvider;
		this.applicationModeService = applicationModeService;
	}

	@GetMapping
	@SuppressWarnings("unchecked")
	public Map<String, Object> page() {
		Map<String, Object> page = service.page();
		if (applicationModeService.isWarrantyEnabled()) {
			return page;
		}
		// step 3: warranties off, the warranty ticket's document left out (its saved row kept)
		Map<String, Object> shown = new LinkedHashMap<>(page);
		shown.put("documents", ((List<Map<String, Object>>) page.get("documents")).stream()
				.filter(d -> !TicketDocumentType.WARRANTY.name().equals(d.get("type"))).collect(Collectors.toList()));
		return shown;
	}

	@PutMapping("/{type}")
	public ResponseEntity<?> save(@PathVariable String type, @RequestBody TicketTemplateDTO template) {
		if (warrantyOff(type)) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", WarrantyAPI.OFF_MESSAGE));
		}
		checkWrite();
		return ResponseEntity.ok(service.save(type, template, currentUserProvider.getCurrentUserName()));
	}

	@DeleteMapping("/{type}")
	public ResponseEntity<?> reset(@PathVariable String type) {
		if (warrantyOff(type)) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", WarrantyAPI.OFF_MESSAGE));
		}
		checkWrite();
		service.reset(type);
		return ResponseEntity.noContent().build();
	}

	/** Step 3: the warranty ticket's template while warranties are off (warranty.enabled not true). */
	private boolean warrantyOff(String type) {
		return TicketDocumentType.of(type) == TicketDocumentType.WARRANTY && !applicationModeService.isWarrantyEnabled();
	}

	private void checkWrite() {
		String permission = ZZDataInitializer.TICKET_TEMPLATES_WRITE_PERMISSION;
		if (!currentUserProvider.currentUserHasPermission(permission)) {
			throw new TicketTemplateException(HttpStatus.FORBIDDEN.value(), "TICKET_TEMPLATE_FORBIDDEN",
					"Saving a ticket template needs the permission " + permission, "permission", permission);
		}
	}

	@ExceptionHandler(TicketTemplateException.class)
	public ResponseEntity<Map<String, Object>> refused(TicketTemplateException e) {
		return ResponseEntity.status(e.getStatus()).body(e.body());
	}
}
