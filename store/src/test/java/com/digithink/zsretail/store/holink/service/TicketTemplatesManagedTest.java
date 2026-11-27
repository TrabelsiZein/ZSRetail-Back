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
 * when it changes; off → on the store's own templates copied aside, deleted, the cursor deleted, an Alert; on → off the
 * templates received kept and the store's own again (LOCAL), an event; a value not sent keeps the saved one; never
 * received: off.
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
	@DisplayName("Off → on: the store's own templates copied aside (dated) and deleted, the cursor deleted, one Alert; on"
			+ " again: nothing; on → off: the templates received kept, LOCAL again, one event")
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
		assertTrue(templates.isEmpty(), "the store's own deleted: the head office's or the built-in default print");
		assertEquals(2, saved.size());
		assertEquals(at, saved.get(0).getSavedAt());
		assertTrue(saved.stream().anyMatch(s -> "SALE".equals(s.getDocumentType()) && "{\"spacing\":\"COMPACT\"}".equals(s.getLayout())
				&& "admin".equals(s.getTemplateUpdatedBy())));
		assertFalse(cursors.containsKey(DataDomain.TICKET_TEMPLATES), "everything pulled again from the start");
		assertTrue(cursors.containsKey(DataDomain.CATALOGUE), "another domain's cursor kept");
		assertEquals(1, log.rows.size());
		assertEquals(EventCode.TICKET_TEMPLATES_MANAGED_ON.name(), log.rows.get(0).getCode());
		assertTrue(log.rows.get(0).getDetails().contains("Ticket de vente"), log.rows.get(0).getDetails());

		managed.received(true, at.plusMinutes(3));
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
}
