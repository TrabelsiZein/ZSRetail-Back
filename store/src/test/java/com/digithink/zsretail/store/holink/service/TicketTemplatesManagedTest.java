package com.digithink.zsretail.store.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.service.AdminEvents;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.support.InMemoryEventLog;
import com.digithink.zsretail.core.tickettemplate.model.TicketTemplate;
import com.digithink.zsretail.core.tickettemplate.repository.TicketTemplateRepository;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;
import com.digithink.zsretail.store.holink.model.DownCursor;
import com.digithink.zsretail.store.holink.model.LinkRight;
import com.digithink.zsretail.store.holink.model.TicketTemplateSaved;
import com.digithink.zsretail.store.holink.repository.DownCursorRepository;
import com.digithink.zsretail.store.holink.repository.LinkRightRepository;
import com.digithink.zsretail.store.holink.repository.TicketTemplateSavedRepository;

/**
 * 3.5.0, ticket templates step 5: "Le siège gère les modèles de tickets" at the store (the tables in memory): saved
 * when it changes; off → on the cursor deleted and the replacement waiting: the store's own templates copied aside and
 * deleted, with an Alert, only when a page of the head office is in hand (replaceIfWaiting); on → off the templates
 * received kept and the store's own again (LOCAL), an event, a waiting replacement dropped; a value not sent keeps the
 * saved one; never received: off.
 */
class TicketTemplatesManagedTest {

	final Map<String, LinkRight> rights = new HashMap<>();
	final Map<String, TicketTemplate> templates = new HashMap<>();
	final List<TicketTemplateSaved> saved = new ArrayList<>();
	final Map<DataDomain, DownCursor> cursors = new HashMap<>();
	final InMemoryEventLog log = new InMemoryEventLog();
	TicketTemplatesManaged managed;

	/** Runs the callback at once (no transaction manager in a unit test). */
	static final class Direct extends TransactionTemplate {
		private static final long serialVersionUID = 1L;

		@Override
		public <T> T execute(TransactionCallback<T> action) {
			return action.doInTransaction((TransactionStatus) null);
		}
	}

	@BeforeEach
	void setUp() {
		LinkRightRepository rightRepository = mock(LinkRightRepository.class);
		when(rightRepository.findByCode(anyString())).thenAnswer(call -> Optional.ofNullable(rights.get(call.getArgument(0))));
		when(rightRepository.save(any(LinkRight.class))).thenAnswer(call -> {
			LinkRight right = call.getArgument(0);
			rights.put(right.getCode(), right);
			return right;
		});
		TicketTemplateRepository templateRepository = mock(TicketTemplateRepository.class);
		when(templateRepository.findAll()).thenAnswer(call -> new ArrayList<>(templates.values()));
		when(templateRepository.save(any(TicketTemplate.class))).thenAnswer(call -> {
			TicketTemplate row = call.getArgument(0);
			templates.put(row.getDocumentType(), row);
			return row;
		});
		doAnswer(call -> templates.remove(((TicketTemplate) call.getArgument(0)).getDocumentType())).when(templateRepository)
				.delete(any(TicketTemplate.class));
		TicketTemplateSavedRepository savedRepository = mock(TicketTemplateSavedRepository.class);
		when(savedRepository.save(any(TicketTemplateSaved.class))).thenAnswer(call -> {
			saved.add(call.getArgument(0));
			return call.getArgument(0);
		});
		DownCursorRepository cursorRepository = mock(DownCursorRepository.class);
		when(cursorRepository.findByDomain(any(DataDomain.class)))
				.thenAnswer(call -> Optional.ofNullable(cursors.get(call.getArgument(0))));
		doAnswer(call -> cursors.remove(((DownCursor) call.getArgument(0)).getDomain())).when(cursorRepository)
				.delete(any(DownCursor.class));
		managed = new TicketTemplatesManaged(rightRepository, templateRepository, savedRepository, cursorRepository,
				new AdminEvents(log.eventLog()), new Direct());
	}

	private TicketTemplate row(String type, String origin) {
		TicketTemplate row = new TicketTemplate();
		row.setDocumentType(type);
		row.setLayout("{\"spacing\":\"COMPACT\"}");
		row.setBlocks("[]");
		row.setFormatVersion(2);
		row.setOrigin(origin);
		row.setUpdatedBy("admin");
		templates.put(type, row);
		return row;
	}

	@Test
	@DisplayName("Never received: off; off received the first time: saved, no transition, no event; a null keeps it")
	void neverReceived() {
		row("SALE", TicketTemplateService.LOCAL);
		assertFalse(managed.managedByHeadOffice());
		managed.received(null, LocalDateTime.now());
		assertTrue(rights.isEmpty(), "an older head office sends nothing: nothing saved");
		managed.received(false, LocalDateTime.now());
		assertEquals(Boolean.FALSE, rights.get(TicketTemplatesManaged.CODE).getGranted());
		assertEquals(1, templates.size(), "the store's own template untouched");
		assertTrue(saved.isEmpty());
		assertTrue(log.rows.isEmpty());
	}

	@Test
	@DisplayName("Off → on: the cursor deleted, the store's own kept until a page is in hand; then copied aside (dated) and"
			+ " deleted, one Alert; on again: nothing; on → off: the templates received kept, LOCAL again, one event")
	void transitions() {
		LocalDateTime at = LocalDateTime.of(2026, 11, 27, 18, 0);
		row("SALE", TicketTemplateService.LOCAL);
		row("CASH_MOVEMENT_SLIP", TicketTemplateService.LOCAL);
		DownCursor cursor = new DownCursor();
		cursor.setDomain(DataDomain.TICKET_TEMPLATES);
		cursor.setCursorValue("12");
		cursors.put(DataDomain.TICKET_TEMPLATES, cursor);
		DownCursor other = new DownCursor();
		other.setDomain(DataDomain.CATALOGUE);
		cursors.put(DataDomain.CATALOGUE, other);

		managed.received(true, at);
		assertTrue(managed.managedByHeadOffice());
		assertTrue(managed.replacementWaiting());
		assertEquals(2, templates.size(), "nothing replaced before the head office's templates are in hand");
		assertTrue(saved.isEmpty());
		assertTrue(log.rows.isEmpty(), "the event comes with the replacement");
		assertFalse(cursors.containsKey(DataDomain.TICKET_TEMPLATES), "everything pulled again from the start");

		LocalDateTime pulledAt = at.plusSeconds(1);
		assertTrue(managed.replaceIfWaiting(pulledAt), "a page in hand: the replacement");
		assertFalse(managed.replacementWaiting());
		assertTrue(templates.isEmpty(), "the store's own deleted: the head office's or the built-in default print");
		assertEquals(2, saved.size());
		assertEquals(pulledAt, saved.get(0).getSavedAt());
		assertTrue(saved.stream().anyMatch(s -> "SALE".equals(s.getDocumentType()) && "{\"spacing\":\"COMPACT\"}".equals(s.getLayout())
				&& "admin".equals(s.getTemplateUpdatedBy())));
		assertFalse(cursors.containsKey(DataDomain.TICKET_TEMPLATES), "everything pulled again from the start");
		assertTrue(cursors.containsKey(DataDomain.CATALOGUE), "another domain's cursor kept");
		assertEquals(1, log.rows.size());
		assertEquals(EventCode.TICKET_TEMPLATES_MANAGED_ON.name(), log.rows.get(0).getCode());
		assertTrue(log.rows.get(0).getDetails().contains("Ticket de vente"), log.rows.get(0).getDetails());

		assertFalse(managed.replaceIfWaiting(at.plusSeconds(2)), "a later page: nothing waits");
		managed.received(true, at.plusMinutes(3));
		assertFalse(managed.replaceIfWaiting(at.plusMinutes(3)), "on again: nothing");
		assertEquals(2, saved.size(), "on again: nothing");
		assertEquals(1, log.rows.size());

		row("SALE", TicketTemplateService.HEAD_OFFICE);
		managed.received(false, at.plusMinutes(6));
		assertFalse(managed.managedByHeadOffice());
		assertEquals(TicketTemplateService.LOCAL, templates.get("SALE").getOrigin(), "kept, the store's own again");
		assertEquals("{\"spacing\":\"COMPACT\"}", templates.get("SALE").getLayout(), "no ticket changes");
		assertEquals(2, log.rows.size());
		assertEquals(EventCode.TICKET_TEMPLATES_MANAGED_OFF.name(), log.rows.get(1).getCode());
	}

	@Test
	@DisplayName("On, then off before any page of the head office (unreachable): the store's own never touched, the"
			+ " replacement dropped, no Alert")
	void offBeforeAnyPull() {
		LocalDateTime at = LocalDateTime.of(2026, 11, 27, 18, 0);
		row("SALE", TicketTemplateService.LOCAL);
		managed.received(true, at);
		assertTrue(managed.replacementWaiting());

		managed.received(false, at.plusMinutes(3));
		assertFalse(managed.replacementWaiting());
		assertFalse(managed.replaceIfWaiting(at.plusMinutes(4)), "off: nothing replaced");
		assertEquals(TicketTemplateService.LOCAL, templates.get("SALE").getOrigin());
		assertTrue(saved.isEmpty());
		assertTrue(log.rows.stream().noneMatch(r -> EventCode.TICKET_TEMPLATES_MANAGED_ON.name().equals(r.getCode())));
		// step 6: the Journal says the store's own templates print, not the defaults
		assertEquals("Le siège ne gère plus les modèles de tickets de ce magasin : aucun modèle reçu, le magasin imprime ses"
				+ " propres modèles (Ticket de vente).", log.rows.get(log.rows.size() - 1).getDetails());
	}
}
