package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.eventlog.service.AdminEvents;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.support.InMemoryEventLog;
import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.core.tickettemplate.TicketDocumentType;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO;
import com.digithink.zsretail.core.tickettemplate.model.TicketTemplate;
import com.digithink.zsretail.core.tickettemplate.repository.TicketTemplateRepository;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;
import com.digithink.zsretail.headoffice.model.Store;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 3.5.0, ticket templates step 5: the head office side of the copies down TICKET_TEMPLATES (the real service, the
 * repository in memory): a save and a reset are changes for every store; a saved template is a record (its code the
 * document type, the built-in texts for the ones left null), a reset or never saved one none (answered as removed);
 * the backfill's targets are the saved templates.
 */
class TicketTemplateDownProviderTest {

	final Map<String, TicketTemplate> rows = new HashMap<>();
	final CopiesDownFeed feed = mock(CopiesDownFeed.class);
	TicketTemplateService service;
	TicketTemplateDownProvider provider;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
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
		CompanyInformationService company = mock(CompanyInformationService.class);
		when(company.get()).thenReturn(new CompanyInformationDTO());
		service = new TicketTemplateService(repository, company, mock(GeneralSetupService.class),
				new AdminEvents(new InMemoryEventLog().eventLog()));
		service.setMode(TestModes.of(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")));
		ObjectProvider<TicketTemplateService> services = mock(ObjectProvider.class);
		when(services.getObject()).thenReturn(service);
		ObjectProvider<CopiesDownFeed> feeds = mock(ObjectProvider.class);
		when(feeds.getIfAvailable()).thenReturn(feed);
		provider = new TicketTemplateDownProvider(services, feeds);
		service.setChanges(provider);
	}

	@Test
	@DisplayName("A save and a reset recorded for every store; the record of a saved template, none for a reset or never"
			+ " saved one; the backfill's targets")
	void copiesDown() {
		assertEquals(DataDomain.TICKET_TEMPLATES, provider.getDomain());
		TicketTemplateDTO sale = service.defaults(TicketDocumentType.SALE);
		sale.getLayout().put("spacing", "COMPACT");
		service.save("SALE", sale, "admin");
		verify(feed).recordChange(eq(DataDomain.TICKET_TEMPLATES), eq("SALE"), any(StoreTargets.class));

		Store store = new Store();
		store.setId(7L);
		Map<String, JsonNode> copies = provider.load(store, Arrays.asList("SALE", "RETURN_VOUCHER", "MENU"));
		assertEquals(1, copies.size(), "the reset or never saved and the unknown codes answered as removed");
		JsonNode copy = copies.get("SALE");
		assertEquals("SALE", copy.path("documentType").asText());
		assertEquals("HEAD_OFFICE", copy.path("origin").asText());
		assertEquals("COMPACT", copy.path("layout").path("spacing").asText());
		assertTrue(copy.toString().contains(TicketTemplateService.BUILT_IN_THANK_YOU), copy.toString());
		assertEquals(Arrays.asList("SALE"), new ArrayList<>(provider.currentTargets().keySet()));

		service.reset("SALE");
		verify(feed, times(2)).recordChange(eq(DataDomain.TICKET_TEMPLATES), eq("SALE"), any(StoreTargets.class));
		assertTrue(provider.load(store, Arrays.asList("SALE")).isEmpty(), "reset: answered as removed");
		assertTrue(provider.currentTargets().isEmpty());
		assertFalse(service.reset("SALE"));
		verify(feed, times(2)).recordChange(eq(DataDomain.TICKET_TEMPLATES), anyString(), any(StoreTargets.class));
		verify(feed, never()).recordChange(eq(DataDomain.CATALOGUE), anyString(), any(StoreTargets.class));
	}
}
