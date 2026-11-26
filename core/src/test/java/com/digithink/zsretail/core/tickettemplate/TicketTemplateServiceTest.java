package com.digithink.zsretail.core.tickettemplate;

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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.service.AdminEvents;
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.support.InMemoryEventLog;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO.ElementDTO;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO.SectionDTO;
import com.digithink.zsretail.core.tickettemplate.model.TicketTemplate;
import com.digithink.zsretail.core.tickettemplate.repository.TicketTemplateRepository;
import com.digithink.zsretail.core.tickettemplate.service.TicketTemplateService;

/**
 * 3.5.0, ticket templates step 2: the templates' service. No row = the default (the catalogue, the linked elements from
 * the old settings); a save is checked (locked blocks, the line's VAT and "TVA par taux", widths, sizes, codes) and
 * normalised (header first, tombola last); a row read back is normalised leniently; a save or a reset is an event.
 */
class TicketTemplateServiceTest {

	static final List<String> SALE_ORDER = Arrays.asList("HEADER", "INFO", "ITEMS", "TOTALS", "VAT_BY_RATE", "PAYMENTS",
			"LOYALTY", "BARCODE", "FOOTER", "TOMBOLA");

	final Map<String, TicketTemplate> rows = new HashMap<>();
	final CompanyInformationDTO company = new CompanyInformationDTO();
	final Map<String, String> settings = new HashMap<>();
	final InMemoryEventLog log = new InMemoryEventLog();
	TicketTemplateService service;

	@BeforeEach
	void setUp() {
		TicketTemplateRepository repository = mock(TicketTemplateRepository.class);
		when(repository.findByDocumentType(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
		when(repository.save(any(TicketTemplate.class))).thenAnswer(call -> {
			TicketTemplate row = call.getArgument(0);
			if (row.getId() == null) {
				row.setId((long) rows.size() + 1);
			}
			rows.put(row.getDocumentType(), row);
			return row;
		});
		doAnswer(call -> rows.remove(((TicketTemplate) call.getArgument(0)).getDocumentType())).when(repository)
				.delete(any(TicketTemplate.class));
		CompanyInformationService companyService = mock(CompanyInformationService.class);
		when(companyService.get()).thenReturn(company);
		GeneralSetupService setup = mock(GeneralSetupService.class);
		when(setup.findValueByCode(anyString())).thenAnswer(call -> settings.get(call.<String>getArgument(0)));
		service = new TicketTemplateService(repository, companyService, setup, new AdminEvents(log.eventLog()));
	}

	static List<String> order(TicketTemplateDTO t) {
		return t.getSections().stream().map(SectionDTO::getCode).collect(Collectors.toList());
	}

	static SectionDTO section(TicketTemplateDTO t, String code) {
		return t.getSections().stream().filter(s -> s.getCode().equals(code)).findFirst().orElse(null);
	}

	static Boolean on(TicketTemplateDTO t, String sectionCode, String elementCode) {
		return section(t, sectionCode).getElements().stream().filter(e -> e.getCode().equals(elementCode)).findFirst()
				.map(ElementDTO::getOn).orElse(null);
	}

	static void set(TicketTemplateDTO t, String sectionCode, String elementCode, boolean value) {
		section(t, sectionCode).getElements().stream().filter(e -> e.getCode().equals(elementCode)).findFirst().get()
				.setOn(value);
	}

	/** The default with a change, as the page sends it. */
	TicketTemplateDTO sent(Consumer<TicketTemplateDTO> change) {
		TicketTemplateDTO t = service.defaults(TicketDocumentType.SALE);
		change.accept(t);
		return t;
	}

	String refusal(Consumer<TicketTemplateDTO> change) {
		TicketTemplateException e = assertThrows(TicketTemplateException.class,
				() -> service.save("SALE", sent(change), "admin"));
		assertEquals(400, e.getStatus());
		assertTrue(rows.isEmpty(), "nothing saved");
		return e.getCode();
	}

	@Test
	@DisplayName("Default: the order of today's ticket, 80 mm, normal, not compact; unit price and VAT by rate off; the"
			+ " linked switches read from Company Information and General setup as the old code reads them")
	void defaults() {
		TicketTemplateDTO d = service.defaults(TicketDocumentType.SALE);
		assertEquals(SALE_ORDER, order(d));
		assertEquals(80, d.getPaperWidth());
		assertEquals("NORMAL", d.getFontSize());
		assertFalse(d.getCompact());
		assertFalse(on(d, "ITEMS", "UNIT_PRICE"));
		assertFalse(section(d, "VAT_BY_RATE").getOn());
		// nothing set: the address no (null = no), the thank-you, the label, the loyalty rows yes (null / absent = yes)
		assertFalse(on(d, "HEADER", "ADDRESS"));
		assertTrue(on(d, "FOOTER", "THANK_YOU"));
		assertTrue(on(d, "FOOTER", "SOFTWARE_LABEL"));
		assertTrue(on(d, "LOYALTY", "EARNED"));
		assertTrue(on(d, "LOYALTY", "BALANCE"));

		company.setReceiptPrintAddress(true);
		company.setReceiptShowThankYou(false);
		company.setReceiptShowSoftwareLabel(false);
		settings.put("TICKET_SHOW_LOYALTY_EARNED", "false");
		settings.put("TICKET_SHOW_LOYALTY_BALANCE", "false");
		d = service.defaults(TicketDocumentType.SALE);
		assertTrue(on(d, "HEADER", "ADDRESS"));
		assertFalse(on(d, "FOOTER", "THANK_YOU"));
		assertFalse(on(d, "FOOTER", "SOFTWARE_LABEL"));
		assertFalse(on(d, "LOYALTY", "EARNED"));
		assertFalse(on(d, "LOYALTY", "BALANCE"));
	}

	@Test
	@DisplayName("The page: the sale ticket with its catalogue, no template yet and its default; the other documents"
			+ " listed as not available yet")
	@SuppressWarnings("unchecked")
	void page() {
		Map<String, Object> page = service.page();
		List<Map<String, Object>> documents = (List<Map<String, Object>>) page.get("documents");
		assertEquals(TicketDocumentType.values().length, documents.size());
		Map<String, Object> sale = documents.get(0);
		assertEquals("SALE", sale.get("type"));
		assertEquals(true, sale.get("available"));
		assertNull(sale.get("template"));
		assertEquals(SALE_ORDER, order((TicketTemplateDTO) sale.get("defaults")));
		assertEquals(SALE_ORDER.size(), ((List<?>) sale.get("sections")).size());
		for (Map<String, Object> other : documents.subList(1, documents.size())) {
			assertEquals(false, other.get("available"), String.valueOf(other.get("type")));
			assertFalse(other.containsKey("template"));
		}
		assertEquals(Arrays.asList(58, 80), page.get("paperWidths"));
		assertEquals(Arrays.asList("SMALL", "NORMAL", "LARGE"), page.get("fontSizes"));
	}

	@Test
	@DisplayName("Refused: a locked section or element off, the line's VAT off without VAT by rate, a width, a size, an"
			+ " unknown or repeated code; nothing saved")
	void refusals() {
		assertEquals("TICKET_TEMPLATE_LOCKED", refusal(t -> set(t, "HEADER", "STORE_NAME", false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusal(t -> set(t, "HEADER", "DUPLICATE", false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusal(t -> set(t, "INFO", "NUMBER", false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusal(t -> set(t, "INFO", "DATE", false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusal(t -> set(t, "ITEMS", "LINES", false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusal(t -> set(t, "TOTALS", "TOTAL_VAT", false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusal(t -> set(t, "TOTALS", "TOTAL", false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusal(t -> set(t, "FOOTER", "MF", false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusal(t -> section(t, "PAYMENTS").setOn(false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusal(t -> section(t, "ITEMS").setOn(false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusal(t -> section(t, "TOMBOLA").setOn(false)));
		assertEquals("TICKET_TEMPLATE_VAT_NEEDED", refusal(t -> set(t, "ITEMS", "VAT", false)));
		assertEquals("TICKET_TEMPLATE_PAPER_WIDTH", refusal(t -> t.setPaperWidth(70)));
		assertEquals("TICKET_TEMPLATE_PAPER_WIDTH", refusal(t -> t.setPaperWidth(null)));
		assertEquals("TICKET_TEMPLATE_FONT_SIZE", refusal(t -> t.setFontSize("HUGE")));
		assertEquals("TICKET_TEMPLATE_UNKNOWN_SECTION", refusal(t -> t.getSections().add(new SectionDTO("QR", true, null))));
		assertEquals("TICKET_TEMPLATE_UNKNOWN_ELEMENT",
				refusal(t -> section(t, "INFO").getElements().add(new ElementDTO("TABLE", true))));
		assertEquals("TICKET_TEMPLATE_DUPLICATE",
				refusal(t -> t.getSections().add(new SectionDTO("BARCODE", true, new ArrayList<>()))));

		TicketTemplateException other = assertThrows(TicketTemplateException.class,
				() -> service.save("RETURN_VOUCHER", sent(t -> { }), "admin"));
		assertEquals("TICKET_TEMPLATE_NOT_AVAILABLE", other.getCode());
		TicketTemplateException unknown = assertThrows(TicketTemplateException.class,
				() -> service.save("MENU", sent(t -> { }), "admin"));
		assertEquals(404, unknown.getStatus());
		assertEquals("TICKET_TEMPLATE_TYPE_UNKNOWN", unknown.getCode());
	}

	@Test
	@DisplayName("Saved: the line's VAT off with VAT by rate on, the switches, width, size, compact kept; the header put"
			+ " back first and the tombola last; a section not sent goes after the one before it; read back the same")
	void saveAndNormalise() {
		TicketTemplateDTO t = sent(x -> {
			section(x, "VAT_BY_RATE").setOn(true);
			set(x, "ITEMS", "VAT", false);
			set(x, "ITEMS", "UNIT_PRICE", true);
			set(x, "HEADER", "LOGO", false);
			section(x, "BARCODE").setOn(false);
			x.setPaperWidth(58);
			x.setFontSize("small");
			x.setCompact(true);
			// the header sent last, the tombola first, the loyalty before the totals, the footer not sent
			SectionDTO header = section(x, "HEADER");
			SectionDTO tombola = section(x, "TOMBOLA");
			SectionDTO loyalty = section(x, "LOYALTY");
			x.getSections().removeIf(s -> Arrays.asList("HEADER", "TOMBOLA", "LOYALTY", "FOOTER").contains(s.getCode()));
			x.getSections().add(0, tombola);
			x.getSections().add(3, loyalty);
			x.getSections().add(header);
		});
		TicketTemplateDTO saved = service.save("sale", t, "admin");
		assertEquals(Arrays.asList("HEADER", "INFO", "ITEMS", "LOYALTY", "TOTALS", "VAT_BY_RATE", "PAYMENTS", "BARCODE",
				"FOOTER", "TOMBOLA"), order(saved));
		assertEquals(58, saved.getPaperWidth());
		assertEquals("SMALL", saved.getFontSize());
		assertTrue(saved.getCompact());
		assertFalse(on(saved, "ITEMS", "VAT"));
		assertTrue(on(saved, "ITEMS", "UNIT_PRICE"));
		assertFalse(on(saved, "HEADER", "LOGO"));
		assertFalse(section(saved, "BARCODE").getOn());
		assertTrue(section(saved, "FOOTER").getOn(), "a section not sent: its default");
		assertEquals("admin", saved.getUpdatedBy());

		TicketTemplateDTO read = service.saved(TicketDocumentType.SALE).get();
		assertEquals(order(saved), order(read));
		assertEquals(saved.getSections(), read.getSections());
		assertEquals(1, rows.get("SALE").getFormatVersion());
		assertEquals("LOCAL", rows.get("SALE").getOrigin());
	}

	@Test
	@DisplayName("A row read back leniently: an unknown code dropped, a locked block and the line's VAT put back on, a"
			+ " wrong width and size the defaults, unreadable blocks the default order")
	void lenientRead() {
		TicketTemplate row = new TicketTemplate();
		row.setDocumentType("SALE");
		row.setPaperWidth(66);
		row.setFontSize("TINY");
		row.setCompact(true);
		row.setHideZeroRows(false);
		row.setOrigin("LOCAL");
		row.setFormatVersion(1);
		row.setBlocks("[{\"code\":\"QR\",\"on\":true},{\"code\":\"TOTALS\",\"on\":true,\"elements\":[{\"code\":\"TOTAL\","
				+ "\"on\":false}]},{\"code\":\"ITEMS\",\"on\":true,\"elements\":[{\"code\":\"VAT\",\"on\":false},"
				+ "{\"code\":\"FOO\",\"on\":true}]}]");
		rows.put("SALE", row);
		TicketTemplateDTO read = service.saved(TicketDocumentType.SALE).get();
		assertEquals(80, read.getPaperWidth());
		assertEquals("NORMAL", read.getFontSize());
		assertTrue(on(read, "TOTALS", "TOTAL"));
		assertTrue(on(read, "ITEMS", "VAT"));
		// TOTALS before ITEMS as in the row; the sections it lacks after the one before them in the catalogue
		assertEquals(Arrays.asList("HEADER", "INFO", "TOTALS", "VAT_BY_RATE", "PAYMENTS", "LOYALTY", "BARCODE", "FOOTER",
				"ITEMS", "TOMBOLA"), order(read));

		row.setBlocks("not json");
		assertEquals(SALE_ORDER, order(service.saved(TicketDocumentType.SALE).get()));
	}

	@Test
	@DisplayName("Event log: the first save (its values), a change (only what changed), a save without change (nothing),"
			+ " a reset (its values of before), a reset without a row (nothing, false)")
	void events() {
		service.save("SALE", sent(t -> { }), "admin");
		EventLogEntry created = log.only("TICKET_TEMPLATE_SAVED");
		assertEquals("TICKET_TEMPLATE", created.getSubjectType());
		assertEquals("SALE", created.getSubjectId());
		assertTrue(created.getDetails().startsWith("Modèle de ticket « Ticket de vente » créé : Papier 80 mm ; Taille Normale ;"
				+ " Compact non ; Ordre En-tête, "), created.getDetails());

		service.save("SALE", sent(t -> {
			t.setCompact(true);
			set(t, "INFO", "CASHIER", false);
		}), "admin");
		assertEquals(2, log.rows.size());
		assertEquals("Modèle de ticket « Ticket de vente » modifié : Compact : non → oui ; Masqués : Adresse, Prix unitaire,"
				+ " TVA par taux → Adresse, Caissier, Prix unitaire, TVA par taux.", log.rows.get(1).getDetails());

		service.save("SALE", service.saved(TicketDocumentType.SALE).get(), "admin");
		assertEquals(2, log.rows.size(), "nothing changed: no event");

		assertTrue(service.reset("SALE"));
		assertTrue(rows.isEmpty());
		assertEquals("TICKET_TEMPLATE_RESET", log.rows.get(2).getCode());
		assertTrue(log.rows.get(2).getDetails().startsWith("Modèle de ticket « Ticket de vente » réinitialisé"));

		assertFalse(service.reset("SALE"));
		assertEquals(3, log.rows.size());
	}
}
