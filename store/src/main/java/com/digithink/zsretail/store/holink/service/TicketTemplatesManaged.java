package com.digithink.zsretail.store.holink.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.eventlog.service.AdminEvents;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.tickettemplate.TicketBlockLabels;
import com.digithink.zsretail.core.tickettemplate.link.TicketTemplateLink;
import com.digithink.zsretail.core.tickettemplate.model.TicketTemplate;
import com.digithink.zsretail.core.tickettemplate.repository.TicketTemplateRepository;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;
import com.digithink.zsretail.store.holink.model.LinkRight;
import com.digithink.zsretail.store.holink.model.TicketTemplateSaved;
import com.digithink.zsretail.store.holink.repository.DownCursorRepository;
import com.digithink.zsretail.store.holink.repository.LinkRightRepository;
import com.digithink.zsretail.store.holink.repository.TicketTemplateSavedRepository;

import lombok.extern.log4j.Log4j2;

/**
 * 3.5.0, ticket templates step 5: "Le siège gère les modèles de tickets", the switch of this store's row on the head
 * office's Stores page, received with each heartbeat answer and saved (hol_link_right, {@link #CODE}): it holds after a
 * restart and while the head office is unreachable; never received: off (the store keeps its own templates).
 * <p>
 * The two transitions, each in one transaction with its event:
 * <ul>
 * <li>off → on: every template of the store's own copied, dated, to ticket_template_saved (a recovery by hand only),
 * then deleted; the cursor of the copies down TICKET_TEMPLATES deleted, so the next cycle pulls every head office
 * template from the start. A document the head office has no template for prints the built-in default.</li>
 * <li>on → off: the templates received are kept (no ticket changes), the store's own again (origin LOCAL), editable.</li>
 * </ul>
 * A store linked to a head office only; none elsewhere (never managed).
 */
@Component
@ConditionalOnInstallation(Installation.LINKED_TO_HEAD_OFFICE)
@Log4j2
public class TicketTemplatesManaged implements TicketTemplateLink {

	public static final String CODE = "TICKET_TEMPLATES_MANAGED";

	private final LinkRightRepository rights;
	private final TicketTemplateRepository templates;
	private final TicketTemplateSavedRepository saved;
	private final DownCursorRepository cursors;
	private final AdminEvents adminEvents;
	private final TransactionTemplate transactions;

	/** The saved value, read once from the table (null: not read yet). */
	private volatile Boolean cache;

	/** The one Spring uses (two constructors: without the mark Spring looks for a default one and the start fails). */
	@Autowired
	public TicketTemplatesManaged(LinkRightRepository rights, TicketTemplateRepository templates,
			TicketTemplateSavedRepository saved, DownCursorRepository cursors, AdminEvents adminEvents,
			PlatformTransactionManager transactionManager) {
		this(rights, templates, saved, cursors, adminEvents, new TransactionTemplate(transactionManager));
	}

	/** With a given transaction template: used by the tests. */
	public TicketTemplatesManaged(LinkRightRepository rights, TicketTemplateRepository templates,
			TicketTemplateSavedRepository saved, DownCursorRepository cursors, AdminEvents adminEvents,
			TransactionTemplate transactions) {
		this.rights = rights;
		this.templates = templates;
		this.saved = saved;
		this.cursors = cursors;
		this.adminEvents = adminEvents;
		this.transactions = transactions;
	}

	@Override
	public boolean managedByHeadOffice() {
		Boolean value = cache;
		if (value == null) {
			value = rights.findByCode(CODE).map(r -> Boolean.TRUE.equals(r.getGranted())).orElse(false);
			cache = value;
		}
		return value;
	}

	/**
	 * A heartbeat answer (heartbeat thread): the switch's value saved when it differs from the saved one, with its
	 * transition. Null (an older head office): the saved value stays.
	 */
	public void received(Boolean managed, LocalDateTime at) {
		if (managed == null) {
			return;
		}
		Optional<LinkRight> found = rights.findByCode(CODE);
		boolean before = found.map(r -> Boolean.TRUE.equals(r.getGranted())).orElse(false);
		if (found.isPresent() && before == managed) {
			cache = managed;
			return;
		}
		transactions.executeWithoutResult(status -> {
			LinkRight right = rights.findByCode(CODE).orElseGet(LinkRight::new);
			right.setCode(CODE);
			right.setGranted(managed);
			right.setReceivedAt(at);
			rights.save(right);
			if (managed && !before) {
				turnOn(at);
			} else if (!managed && before) {
				turnOff();
			}
		});
		cache = managed;
		log.info("Head office link: the ticket templates are now {}", managed ? "managed by the head office" : "the store's");
	}

	/** Off → on: the store's own templates kept aside and deleted, the cursor deleted (everything pulled again). */
	private void turnOn(LocalDateTime at) {
		List<String> names = new ArrayList<>();
		for (TicketTemplate row : templates.findAll()) {
			TicketTemplateSaved copy = new TicketTemplateSaved();
			copy.setDocumentType(row.getDocumentType());
			copy.setLayout(row.getLayout());
			copy.setBlocks(row.getBlocks());
			copy.setFormatVersion(row.getFormatVersion());
			copy.setOrigin(row.getOrigin());
			copy.setTemplateUpdatedAt(row.getUpdatedAt());
			copy.setTemplateUpdatedBy(row.getUpdatedBy());
			copy.setSavedAt(at);
			copy.setActive(true);
			saved.save(copy);
			names.add(TicketBlockLabels.fr(row.getDocumentType()));
			templates.delete(row);
		}
		cursors.findByDomain(DataDomain.TICKET_TEMPLATES).ifPresent(cursors::delete);
		adminEvents.ticketTemplatesManagedOn(names);
	}

	/** On → off: the templates received kept, the store's own again. */
	private void turnOff() {
		List<String> names = new ArrayList<>();
		for (TicketTemplate row : templates.findAll()) {
			if (TicketTemplateService.HEAD_OFFICE.equals(row.getOrigin())) {
				row.setOrigin(TicketTemplateService.LOCAL);
				templates.save(row);
				names.add(TicketBlockLabels.fr(row.getDocumentType()));
			}
		}
		adminEvents.ticketTemplatesManagedOff(names);
	}
}
