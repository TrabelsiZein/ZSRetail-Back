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
 * The transitions:
 * <ul>
 * <li>off → on: the replacement waits ({@link #REPLACE_CODE}) and the cursor of the copies down TICKET_TEMPLATES is
 * deleted; the heartbeat that brought the switch pulls the domain at once (HeartbeatJob). Only when a page of the head
 * office is in hand ({@link #replaceIfWaiting}, TicketTemplateDownHandler before it applies the page): every template of
 * the store's own copied, dated, to ticket_template_saved (a recovery by hand only), then deleted, with the event. The
 * head office unreachable: the store keeps printing its own templates until the first pull delivered. A document the
 * head office has no template for prints the built-in default.</li>
 * <li>on → off: the templates received are kept (no ticket changes), the store's own again (origin LOCAL), editable; a
 * replacement still waiting is dropped (the store's own stay as they are).</li>
 * </ul>
 * A store linked to a head office only; none elsewhere (never managed).
 */
@Component
@ConditionalOnInstallation(Installation.LINKED_TO_HEAD_OFFICE)
@Log4j2
public class TicketTemplatesManaged implements TicketTemplateLink {

	public static final String CODE = "TICKET_TEMPLATES_MANAGED";

	/** Granted: the switch turned on, the store's own templates not yet replaced by the head office's (hol_link_right). */
	public static final String REPLACE_CODE = "TICKET_TEMPLATES_REPLACE";

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
				turnOff(at);
			}
		});
		cache = managed;
		log.info("Head office link: the ticket templates are now {}", managed ? "managed by the head office (the store's own"
				+ " print until the head office's are pulled)" : "the store's");
	}

	/** Off → on: the replacement waits for the head office's templates; the cursor deleted (everything pulled again). */
	private void turnOn(LocalDateTime at) {
		mark(true, at);
		cursors.findByDomain(DataDomain.TICKET_TEMPLATES).ifPresent(cursors::delete);
	}

	private void mark(boolean waiting, LocalDateTime at) {
		LinkRight marker = rights.findByCode(REPLACE_CODE).orElseGet(LinkRight::new);
		marker.setCode(REPLACE_CODE);
		marker.setGranted(waiting);
		marker.setReceivedAt(at);
		rights.save(marker);
	}

	/** The switch on and the store's own templates not replaced yet. */
	public boolean replacementWaiting() {
		return rights.findByCode(REPLACE_CODE).map(r -> Boolean.TRUE.equals(r.getGranted())).orElse(false);
	}

	/**
	 * A page of the head office in hand (TicketTemplateDownHandler, before it applies it): when the replacement waits, the
	 * store's own templates copied aside, dated, and deleted, the marker cleared, the event; in one transaction. False when
	 * nothing waited.
	 */
	public boolean replaceIfWaiting(LocalDateTime at) {
		if (!managedByHeadOffice() || !replacementWaiting()) {
			return false;
		}
		transactions.executeWithoutResult(status -> replace(at));
		log.info("Head office link: the store's own ticket templates kept aside and replaced by the head office's");
		return true;
	}

	private void replace(LocalDateTime at) {
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
		mark(false, at);
		adminEvents.ticketTemplatesManagedOn(names);
	}

	/** On → off: the templates received kept, the store's own again; a replacement still waiting dropped. */
	private void turnOff(LocalDateTime at) {
		if (replacementWaiting()) {
			mark(false, at);
		}
		List<String> names = new ArrayList<>();
		List<String> own = new ArrayList<>(); // step 6: the store's own, kept (a replacement that never came, a carried row)
		for (TicketTemplate row : templates.findAll()) {
			if (TicketTemplateService.HEAD_OFFICE.equals(row.getOrigin())) {
				row.setOrigin(TicketTemplateService.LOCAL);
				templates.save(row);
				names.add(TicketBlockLabels.fr(row.getDocumentType()));
			} else {
				own.add(TicketBlockLabels.fr(row.getDocumentType()));
			}
		}
		adminEvents.ticketTemplatesManagedOff(names, own);
	}
}
