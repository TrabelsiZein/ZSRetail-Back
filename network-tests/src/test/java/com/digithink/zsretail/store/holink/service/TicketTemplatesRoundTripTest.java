package com.digithink.zsretail.store.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.service.AdminEvents;
import com.digithink.zsretail.core.link.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.support.InMemoryEventLog;
import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.core.tickettemplate.TicketDocumentType;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateException;
import com.digithink.zsretail.core.tickettemplate.model.TicketTemplate;
import com.digithink.zsretail.core.tickettemplate.repository.TicketTemplateRepository;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.CopiesDownFeed;
import com.digithink.zsretail.headoffice.service.InMemoryDownTables;
import com.digithink.zsretail.headoffice.service.TicketTemplateDownProvider;
import com.digithink.zsretail.store.holink.model.DownCursor;
import com.digithink.zsretail.store.holink.model.LinkRight;
import com.digithink.zsretail.store.holink.model.TicketTemplateSaved;
import com.digithink.zsretail.store.holink.repository.DownCursorRepository;
import com.digithink.zsretail.store.holink.repository.LinkRightRepository;
import com.digithink.zsretail.store.holink.repository.TicketTemplateSavedRepository;

/**
 * 3.5.0, ticket templates step 5: a head office and a store whose switch "Le siège gère les modèles de tickets" is on,
 * over the real copies down (the head office's service, TicketTemplateDownProvider and CopiesDownFeed; the store's
 * service, TicketTemplatesManaged and TicketTemplateDownHandler; the tables in memory). The heartbeat's value is the
 * store row's switch (HeadOfficeHeartbeatAPI), the pull runs as CopiesDownPuller does (skipped while the handler is not
 * active, from the saved cursor). The store's GET /ticket-templates (page()) is what its till's builder prints from
 * (src/libs/ticket, checked by tools/ticket-render-check.mjs in ZSRetail-Front).
 */
class TicketTemplatesRoundTripTest {

	private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 27, 18, 0);

	private final Map<String, TicketTemplate> hoRows = new HashMap<>();
	private final Map<String, TicketTemplate> storeRows = new HashMap<>();
	private final Map<String, LinkRight> rights = new HashMap<>();
	private final List<TicketTemplateSaved> savedAside = new ArrayList<>();
	private final Map<DataDomain, DownCursor> cursors = new HashMap<>();
	private final InMemoryEventLog storeLog = new InMemoryEventLog();

	private TicketTemplateService headOffice;
	private TicketTemplateService store;
	private TicketTemplatesManaged managed;
	private TicketTemplateDownHandler handler;
	private CopiesDownFeed feed;
	private Store franchise;
	private int minutes;

	/** Runs the callback at once (no transaction manager here). */
	static final class Direct extends TransactionTemplate {
		private static final long serialVersionUID = 1L;

		@Override
		public <T> T execute(TransactionCallback<T> action) {
			return action.doInTransaction((TransactionStatus) null);
		}
	}

	private static TicketTemplateRepository templates(Map<String, TicketTemplate> rows) {
		TicketTemplateRepository repository = mock(TicketTemplateRepository.class);
		when(repository.findByDocumentType(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
		when(repository.findAll()).thenAnswer(call -> new ArrayList<>(rows.values()));
		when(repository.save(any(TicketTemplate.class))).thenAnswer(call -> {
			TicketTemplate row = call.getArgument(0);
			rows.put(row.getDocumentType(), row);
			return row;
		});
		doAnswer(call -> rows.remove(((TicketTemplate) call.getArgument(0)).getDocumentType())).when(repository)
				.delete(any(TicketTemplate.class));
		return repository;
	}

	private static CompanyInformationService company() {
		CompanyInformationService company = mock(CompanyInformationService.class);
		when(company.get()).thenReturn(new CompanyInformationDTO());
		return company;
	}

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		// the head office: its service, the provider and a real feed
		headOffice = new TicketTemplateService(templates(hoRows), company(), mock(GeneralSetupService.class),
				new AdminEvents(new InMemoryEventLog().eventLog()));
		headOffice.setMode(TestModes.of(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")));
		ObjectProvider<TicketTemplateService> services = mock(ObjectProvider.class);
		when(services.getObject()).thenReturn(headOffice);
		CopiesDownFeed[] feedRef = new CopiesDownFeed[1];
		ObjectProvider<CopiesDownFeed> feeds = mock(ObjectProvider.class);
		when(feeds.getIfAvailable()).thenAnswer(call -> feedRef[0]);
		TicketTemplateDownProvider provider = new TicketTemplateDownProvider(services, feeds);
		headOffice.setChanges(provider);
		feed = new InMemoryDownTables().feed(Collections.singletonList(provider));
		feedRef[0] = feed;
		feed.initialise();
		franchise = new Store();
		franchise.setId(7L);
		franchise.setCode("FR1");
		franchise.setActive(true);

		// the store: its service, the switch (hol_link_right), the recovery table, the cursors, the handler
		TicketTemplateRepository storeTemplates = templates(storeRows);
		store = new TicketTemplateService(storeTemplates, company(), mock(GeneralSetupService.class),
				new AdminEvents(storeLog.eventLog()));
		LinkRightRepository rightRepository = mock(LinkRightRepository.class);
		when(rightRepository.findByCode(anyString())).thenAnswer(call -> Optional.ofNullable(rights.get(call.getArgument(0))));
		when(rightRepository.save(any(LinkRight.class))).thenAnswer(call -> {
			LinkRight right = call.getArgument(0);
			rights.put(right.getCode(), right);
			return right;
		});
		TicketTemplateSavedRepository savedRepository = mock(TicketTemplateSavedRepository.class);
		when(savedRepository.save(any(TicketTemplateSaved.class))).thenAnswer(call -> {
			savedAside.add(call.getArgument(0));
			return call.getArgument(0);
		});
		DownCursorRepository cursorRepository = mock(DownCursorRepository.class);
		when(cursorRepository.findByDomain(any(DataDomain.class)))
				.thenAnswer(call -> Optional.ofNullable(cursors.get(call.getArgument(0))));
		doAnswer(call -> cursors.remove(((DownCursor) call.getArgument(0)).getDomain())).when(cursorRepository)
				.delete(any(DownCursor.class));
		managed = new TicketTemplatesManaged(rightRepository, storeTemplates, savedRepository, cursorRepository,
				new AdminEvents(storeLog.eventLog()), new Direct());
		store.setLink(managed);
		handler = new TicketTemplateDownHandler(store, managed);
	}

	// ─── the network ─────────────────────────────────────────────────

	/** The store's heartbeat: the head office answers the store row's switch (HeadOfficeHeartbeatAPI). */
	private void heartbeat() {
		minutes += 1;
		managed.received(Boolean.TRUE.equals(franchise.getManagesTicketTemplates()), AT.plusMinutes(minutes));
	}

	/** One cycle of CopiesDownPuller for TICKET_TEMPLATES: none while the handler is not active; from the saved cursor. */
	private void pull() {
		if (!handler.active()) {
			return;
		}
		DownCursor cursor = cursors.get(DataDomain.TICKET_TEMPLATES);
		CopiesDownAnswerDTO page = feed.pull(franchise, DataDomain.TICKET_TEMPLATES.name(),
				cursor == null ? "" : cursor.getCursorValue(), 500);
		handler.apply(page.getRecords(), page.getRemoved());
		DownCursor next = cursor == null ? new DownCursor() : cursor;
		next.setDomain(DataDomain.TICKET_TEMPLATES);
		next.setCursorValue(page.getCursor());
		cursors.put(DataDomain.TICKET_TEMPLATES, next);
	}

	/** The switch of the store's row at the head office, then a heartbeat and a pull. */
	private void switchManaged(boolean on) {
		franchise.setManagesTicketTemplates(on);
		heartbeat();
		pull();
	}

	// ─── the two installations' templates ───────────────────────────

	private TicketTemplateDTO withSpacing(TicketTemplateService service, String spacing) {
		TicketTemplateDTO t = service.defaults(TicketDocumentType.SALE);
		t.getLayout().put("spacing", spacing);
		return t;
	}

	private String storeSpacing() {
		return store.saved(TicketDocumentType.SALE).map(t -> String.valueOf(t.getLayout().get("spacing"))).orElse(null);
	}

	/** What the store's till prints from: GET /ticket-templates, the sale ticket's template (null: its default). */
	@SuppressWarnings("unchecked")
	private TicketTemplateDTO storePageSale() {
		List<Map<String, Object>> documents = (List<Map<String, Object>>) store.page().get("documents");
		return (TicketTemplateDTO) documents.stream().filter(d -> "SALE".equals(d.get("type"))).findFirst().get().get("template");
	}

	/** The store's own template before the head office manages it: AIRY. */
	private void storeOwnSale() {
		store.save("SALE", withSpacing(store, "AIRY"), "admin-magasin");
		assertEquals(TicketTemplateService.LOCAL, storeRows.get("SALE").getOrigin());
	}

	// ─── the four steps ──────────────────────────────────────────────

	@Test
	@DisplayName("The head office saves the sale ticket: the store receives it (HEAD_OFFICE), its page gives it to the"
			+ " till's builder, read-only; the store's own template was put aside, a save there is refused 403")
	void headOfficeSaveReachesTheStore() {
		storeOwnSale();
		switchManaged(true);
		assertTrue(storeRows.isEmpty(), "the store's own template replaced (the head office has none yet)");
		assertEquals(1, savedAside.size());
		assertEquals("SALE", savedAside.get(0).getDocumentType());

		headOffice.save("SALE", withSpacing(headOffice, "COMPACT"), "admin-siege");
		pull();

		assertEquals("COMPACT", storeSpacing());
		assertEquals(TicketTemplateService.HEAD_OFFICE, storeRows.get("SALE").getOrigin());
		TicketTemplateDTO printed = storePageSale();
		assertEquals("COMPACT", printed.getLayout().get("spacing"), "the till's builder reads the head office's template");
		assertTrue(storeRows.get("SALE").getBlocks().contains(TicketTemplateService.BUILT_IN_THANK_YOU),
				"the head office's null thank-you sent as the built-in text, never the store's own");
		assertEquals(Boolean.TRUE, store.page().get("managedByHeadOffice"));
		TicketTemplateException refused = assertThrows(TicketTemplateException.class,
				() -> store.save("SALE", withSpacing(store, "AIRY"), "admin-magasin"));
		assertEquals(403, refused.getStatus());
		assertEquals("TICKET_TEMPLATE_MANAGED", refused.getCode());
		assertThrows(TicketTemplateException.class, () -> store.reset("SALE"));
		assertEquals("COMPACT", storeSpacing(), "nothing changed by the refused save");
	}

	@Test
	@DisplayName("A reset at the head office: the store's row removed, the store prints the built-in default")
	void headOfficeResetGivesTheDefault() {
		switchManaged(true);
		headOffice.save("SALE", withSpacing(headOffice, "COMPACT"), "admin-siege");
		pull();
		assertEquals("COMPACT", storeSpacing());

		headOffice.reset("SALE");
		pull();

		assertFalse(storeRows.containsKey("SALE"), "the row received deleted");
		assertNull(storePageSale(), "no template: the till prints the default");
		assertEquals(store.defaults(TicketDocumentType.SALE).getLayout(), headOffice.defaults(TicketDocumentType.SALE).getLayout(),
				"the store's default is the built-in one");
	}

	@Test
	@DisplayName("Switch off: the store keeps the rows received (its own again) and can save; nothing more is pulled")
	void switchOffKeepsTheRows() {
		switchManaged(true);
		headOffice.save("SALE", withSpacing(headOffice, "COMPACT"), "admin-siege");
		pull();

		switchManaged(false);
		assertEquals("COMPACT", storeSpacing(), "kept: no ticket changes");
		assertEquals(TicketTemplateService.LOCAL, storeRows.get("SALE").getOrigin());
		assertEquals(Boolean.FALSE, store.page().get("managedByHeadOffice"));
		assertFalse(handler.active());

		headOffice.save("SALE", withSpacing(headOffice, "NORMAL"), "admin-siege");
		pull();
		assertEquals("COMPACT", storeSpacing(), "a head office change no longer reaches the store");

		store.save("SALE", withSpacing(store, "AIRY"), "admin-magasin");
		assertEquals("AIRY", storeSpacing());
		assertEquals(TicketTemplateService.LOCAL, storeRows.get("SALE").getOrigin());
		assertTrue(storeLog.rows.stream().anyMatch(r -> EventCode.TICKET_TEMPLATES_MANAGED_OFF.name().equals(r.getCode())));
	}

	@Test
	@DisplayName("Switch on again: the store's own rows copied to ticket_template_saved and replaced by the head office's,"
			+ " pulled again from the start")
	void switchOnAgainReplacesTheStoreRows() {
		switchManaged(true);
		headOffice.save("SALE", withSpacing(headOffice, "COMPACT"), "admin-siege");
		pull();
		switchManaged(false);
		store.save("SALE", withSpacing(store, "AIRY"), "admin-magasin");
		int asideBefore = savedAside.size();

		switchManaged(true);

		assertEquals(asideBefore + 1, savedAside.size());
		TicketTemplateSaved aside = savedAside.get(savedAside.size() - 1);
		assertEquals("SALE", aside.getDocumentType());
		assertTrue(aside.getLayout().contains("AIRY"), aside.getLayout());
		assertEquals("admin-magasin", aside.getTemplateUpdatedBy());
		assertEquals("COMPACT", storeSpacing(), "the head office's again, its cursor reset: pulled from the start");
		assertEquals(TicketTemplateService.HEAD_OFFICE, storeRows.get("SALE").getOrigin());
		assertEquals(2, storeLog.rows.stream().filter(r -> EventCode.TICKET_TEMPLATES_MANAGED_ON.name().equals(r.getCode())).count());
	}
}
