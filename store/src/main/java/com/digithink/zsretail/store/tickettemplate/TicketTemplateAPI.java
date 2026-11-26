package com.digithink.zsretail.store.tickettemplate;

import java.util.Map;

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

import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.ZZDataInitializer;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateException;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;

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
 */
@RestController
@RequestMapping("ticket-templates")
public class TicketTemplateAPI {

	private final TicketTemplateService service;
	private final CurrentUserProvider currentUserProvider;

	public TicketTemplateAPI(TicketTemplateService service, CurrentUserProvider currentUserProvider) {
		this.service = service;
		this.currentUserProvider = currentUserProvider;
	}

	@GetMapping
	public Map<String, Object> page() {
		return service.page();
	}

	@PutMapping("/{type}")
	public TicketTemplateDTO save(@PathVariable String type, @RequestBody TicketTemplateDTO template) {
		checkWrite();
		return service.save(type, template, currentUserProvider.getCurrentUserName());
	}

	@DeleteMapping("/{type}")
	public ResponseEntity<Void> reset(@PathVariable String type) {
		checkWrite();
		service.reset(type);
		return ResponseEntity.noContent().build();
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
