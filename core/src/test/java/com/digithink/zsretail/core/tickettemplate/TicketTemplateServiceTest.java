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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * 3.5.0, ticket templates steps 2 and 2b: the templates' service. No row = the default (the catalogue, every option
 * today's, the linked elements from the old settings); a save is checked (locked blocks, the line's VAT and "TVA par
 * taux", the layout and element options, codes) and normalised (header first, tombola last); a row read back is
 * normalised leniently (format 1 too); a save or a reset is an event.
 */
class TicketTemplateServiceTest {

	static final List<String> SALE_ORDER = Arrays.asList("HEADER", "TOP_TEXT", "INFO", "ITEMS", "TOTALS", "VAT_BY_RATE", "PAYMENTS",
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

	static ElementDTO element(TicketTemplateDTO t, String sectionCode, String elementCode) {
		return section(t, sectionCode).getElements().stream().filter(e -> e.getCode().equals(elementCode)).findFirst().get();
	}

	static Boolean on(TicketTemplateDTO t, String sectionCode, String elementCode) {
		return element(t, sectionCode, elementCode).getOn();
	}

	static void set(TicketTemplateDTO t, String sectionCode, String elementCode, boolean value) {
		element(t, sectionCode, elementCode).setOn(value);
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
	@DisplayName("Default: the order of today's ticket; the layout 80 mm, normal size, airy, no blank line, full lines;"
			+ " every option today's (the logo's width and height null, centred; sizes medium; names wrapped); item code,"
			+ " barcode, unit price and VAT by rate off, the promotion's name on; the linked switches from the old settings")
	void defaults() {
		TicketTemplateDTO d = service.defaults(TicketDocumentType.SALE);
		assertEquals(SALE_ORDER, order(d));
		assertEquals(2, d.getFormatVersion());
		Map<String, Object> layout = new LinkedHashMap<>();
		layout.put("paperWidth", 80);
		layout.put("fontSize", "NORMAL");
		layout.put("spacing", "AIRY");
		layout.put("feedLines", 0);
		layout.put("separator", "LINE");
		assertEquals(layout, d.getLayout());
		Map<String, Object> logo = new LinkedHashMap<>();
		logo.put("width", null);
		logo.put("maxHeight", null);
		logo.put("align", "CENTER");
		assertEquals(logo, element(d, "HEADER", "LOGO").getOptions());
		assertEquals(Collections.singletonMap("size", "MEDIUM"), element(d, "HEADER", "STORE_NAME").getOptions());
		assertEquals(Collections.singletonMap("size", "MEDIUM"), element(d, "TOTALS", "TOTAL").getOptions());
		assertEquals(Collections.singletonMap("name", "WRAP"), element(d, "ITEMS", "LINES").getOptions());
		assertTrue(element(d, "INFO", "CASHIER").getOptions().isEmpty());
		assertEquals(Arrays.asList("LINES", "ITEM_CODE", "ITEM_BARCODE", "UNIT_PRICE", "DISCOUNT", "PROMOTION_NAME", "VAT"),
				section(d, "ITEMS").getElements().stream().map(ElementDTO::getCode).collect(Collectors.toList()));
		assertFalse(on(d, "ITEMS", "ITEM_CODE"));
		assertFalse(on(d, "ITEMS", "ITEM_BARCODE"));
		assertFalse(on(d, "ITEMS", "UNIT_PRICE"));
		assertTrue(on(d, "ITEMS", "PROMOTION_NAME"));
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
	@DisplayName("The page: the sale ticket with its layout options, its sections, no template yet and its default; the"
			+ " return voucher and the cash refund slip available (step 3), the others listed as not available yet")
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
		assertEquals(Arrays.asList("paperWidth", "fontSize", "spacing", "feedLines", "separator"),
				((List<TicketBlocks.Option>) sale.get("layout")).stream().map(TicketBlocks.Option::getKey)
						.collect(Collectors.toList()));
		assertEquals(Arrays.asList("SALE", "RETURN_VOUCHER", "CASH_REFUND", "WARRANTY", "TOMBOLA_SLIP"),
				documents.subList(0, 5).stream().map(d -> d.get("type")).collect(Collectors.toList()));
		assertEquals(Arrays.asList(true, true, true, true, true), documents.subList(0, 5).stream().map(d -> d.get("available"))
				.collect(Collectors.toList()));
		assertTrue(((List<?>) documents.get(4).get("layout")).isEmpty(), "the tombola slip has no layout (the sale ticket's)");
		for (Map<String, Object> other : documents.subList(5, documents.size())) {
			assertEquals(false, other.get("available"), String.valueOf(other.get("type")));
			assertFalse(other.containsKey("template"));
		}
		assertEquals(2, page.get("formatVersion"));
	}

	@Test
	@DisplayName("Refused: a locked section or element off, the line's VAT off without VAT by rate, a layout option or an"
			+ " element option out of its values, an unknown option, an unknown or repeated code; nothing saved")
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
		assertEquals("TICKET_TEMPLATE_VAT_NEEDED", refusal(t -> set(t, "ITEMS", "VAT", false)));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> t.getLayout().put("paperWidth", 70)));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> t.getLayout().put("paperWidth", null)));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> t.getLayout().put("fontSize", "HUGE")));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> t.getLayout().put("spacing", "TIGHT")));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> t.getLayout().put("feedLines", 7)));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> t.getLayout().put("feedLines", -1)));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> t.getLayout().put("separator", "STARS")));
		assertEquals("TICKET_TEMPLATE_UNKNOWN_OPTION", refusal(t -> t.getLayout().put("margin", 2)));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> element(t, "HEADER", "LOGO").getOptions().put("width", 19)));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> element(t, "HEADER", "LOGO").getOptions().put("width", 101)));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> element(t, "HEADER", "LOGO").getOptions().put("maxHeight", 61)));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> element(t, "HEADER", "LOGO").getOptions().put("align", "TOP")));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> element(t, "TOTALS", "TOTAL").getOptions().put("size", "HUGE")));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> element(t, "ITEMS", "LINES").getOptions().put("name", "HIDE")));
		assertEquals("TICKET_TEMPLATE_UNKNOWN_OPTION",
				refusal(t -> element(t, "INFO", "CASHIER").getOptions().put("size", "LARGE")));
		assertEquals("TICKET_TEMPLATE_UNKNOWN_SECTION", refusal(t -> t.getSections().add(new SectionDTO("QR", true, null))));
		assertEquals("TICKET_TEMPLATE_UNKNOWN_ELEMENT",
				refusal(t -> section(t, "INFO").getElements().add(new ElementDTO("TABLE", true))));
		assertEquals("TICKET_TEMPLATE_DUPLICATE",
				refusal(t -> t.getSections().add(new SectionDTO("BARCODE", true, new ArrayList<>()))));

		TicketTemplateException other = assertThrows(TicketTemplateException.class,
				() -> service.save("SESSION_REPORT", sent(t -> { }), "admin"));
		assertEquals("TICKET_TEMPLATE_NOT_AVAILABLE", other.getCode());
		TicketTemplateException unknown = assertThrows(TicketTemplateException.class,
				() -> service.save("MENU", sent(t -> { }), "admin"));
		assertEquals(404, unknown.getStatus());
		assertEquals("TICKET_TEMPLATE_TYPE_UNKNOWN", unknown.getCode());
	}

	@Test
	@DisplayName("Saved: the layout and the options kept (a number sent as 58.0 taken as 58), the line's VAT off with VAT"
			+ " by rate on, the switches; the header put back first and the tombola last; a section not sent goes after the"
			+ " one before it; an element without its options gets them; read back the same; format 2")
	void saveAndNormalise() {
		TicketTemplateDTO t = sent(x -> {
			section(x, "VAT_BY_RATE").setOn(true);
			set(x, "ITEMS", "VAT", false);
			set(x, "ITEMS", "UNIT_PRICE", true);
			set(x, "ITEMS", "ITEM_CODE", true);
			set(x, "ITEMS", "PROMOTION_NAME", false);
			set(x, "HEADER", "LOGO", false);
			section(x, "BARCODE").setOn(false);
			x.getLayout().put("paperWidth", 58.0);
			x.getLayout().put("spacing", "NORMAL");
			x.getLayout().put("feedLines", 3);
			x.getLayout().put("separator", "DASHES");
			x.getLayout().remove("fontSize");
			element(x, "HEADER", "LOGO").getOptions().put("width", 50);
			element(x, "HEADER", "LOGO").getOptions().put("maxHeight", 20);
			element(x, "HEADER", "LOGO").getOptions().put("align", "LEFT");
			element(x, "TOTALS", "TOTAL").getOptions().put("size", "LARGE");
			element(x, "ITEMS", "LINES").getOptions().put("name", "CUT");
			element(x, "HEADER", "STORE_NAME").setOptions(null);
			// the header sent last, the tombola first, the loyalty before the totals, the footer not sent
			SectionDTO header = section(x, "HEADER");
			SectionDTO tombola = section(x, "TOMBOLA");
			SectionDTO loyalty = section(x, "LOYALTY");
			x.getSections().removeIf(s -> Arrays.asList("HEADER", "TOMBOLA", "LOYALTY", "FOOTER").contains(s.getCode()));
			x.getSections().add(0, tombola);
			x.getSections().add(4, loyalty);
			x.getSections().add(header);
		});
		TicketTemplateDTO saved = service.save("sale", t, "admin");
		assertEquals(Arrays.asList("HEADER", "TOP_TEXT", "INFO", "ITEMS", "LOYALTY", "TOTALS", "VAT_BY_RATE", "PAYMENTS", "BARCODE",
				"FOOTER", "TOMBOLA"), order(saved));
		assertEquals(58, saved.getLayout().get("paperWidth"));
		assertEquals("NORMAL", saved.getLayout().get("fontSize"), "an option not sent: its default");
		assertEquals("NORMAL", saved.getLayout().get("spacing"));
		assertEquals(3, saved.getLayout().get("feedLines"));
		assertEquals("DASHES", saved.getLayout().get("separator"));
		assertEquals(50, element(saved, "HEADER", "LOGO").getOptions().get("width"));
		assertEquals(20, element(saved, "HEADER", "LOGO").getOptions().get("maxHeight"));
		assertEquals("LEFT", element(saved, "HEADER", "LOGO").getOptions().get("align"));
		assertEquals("LARGE", element(saved, "TOTALS", "TOTAL").getOptions().get("size"));
		assertEquals("CUT", element(saved, "ITEMS", "LINES").getOptions().get("name"));
		assertEquals(Collections.singletonMap("size", "MEDIUM"), element(saved, "HEADER", "STORE_NAME").getOptions());
		assertFalse(on(saved, "ITEMS", "VAT"));
		assertTrue(on(saved, "ITEMS", "UNIT_PRICE"));
		assertTrue(on(saved, "ITEMS", "ITEM_CODE"));
		assertFalse(on(saved, "ITEMS", "PROMOTION_NAME"));
		assertFalse(on(saved, "HEADER", "LOGO"));
		assertFalse(section(saved, "BARCODE").getOn());
		assertTrue(section(saved, "FOOTER").getOn(), "a section not sent: its default");
		assertEquals("admin", saved.getUpdatedBy());

		TicketTemplateDTO read = service.saved(TicketDocumentType.SALE).get();
		assertEquals(order(saved), order(read));
		assertEquals(saved.getSections(), read.getSections());
		assertEquals(saved.getLayout(), read.getLayout());
		assertEquals(2, rows.get("SALE").getFormatVersion());
		assertEquals("LOCAL", rows.get("SALE").getOrigin());
	}

	@Test
	@DisplayName("A row read back leniently: an unknown code or option dropped, a locked block and the line's VAT put back"
			+ " on, a wrong value its default, unreadable JSON the default; a format 1 layout (compact) read as COMPACT")
	void lenientRead() {
		TicketTemplate row = new TicketTemplate();
		row.setDocumentType("SALE");
		row.setLayout("{\"paperWidth\":66,\"fontSize\":\"TINY\",\"compact\":true,\"margin\":3}");
		row.setOrigin("LOCAL");
		row.setFormatVersion(1);
		row.setBlocks("[{\"code\":\"QR\",\"on\":true},{\"code\":\"TOTALS\",\"on\":true,\"elements\":[{\"code\":\"TOTAL\","
				+ "\"on\":false,\"options\":{\"size\":\"HUGE\",\"color\":\"red\"}}]},{\"code\":\"ITEMS\",\"on\":true,"
				+ "\"elements\":[{\"code\":\"VAT\",\"on\":false},{\"code\":\"FOO\",\"on\":true}]}]");
		rows.put("SALE", row);
		TicketTemplateDTO read = service.saved(TicketDocumentType.SALE).get();
		assertEquals(80, read.getLayout().get("paperWidth"));
		assertEquals("NORMAL", read.getLayout().get("fontSize"));
		assertEquals("COMPACT", read.getLayout().get("spacing"), "format 1: compact = COMPACT");
		assertFalse(read.getLayout().containsKey("compact"));
		assertFalse(read.getLayout().containsKey("margin"));
		assertTrue(on(read, "TOTALS", "TOTAL"));
		assertEquals(Collections.singletonMap("size", "MEDIUM"), element(read, "TOTALS", "TOTAL").getOptions());
		assertTrue(on(read, "ITEMS", "VAT"));
		// TOTALS before ITEMS as in the row; the sections it lacks after the one before them in the catalogue
		assertEquals(Arrays.asList("HEADER", "TOP_TEXT", "INFO", "TOTALS", "VAT_BY_RATE", "PAYMENTS", "LOYALTY", "BARCODE", "FOOTER",
				"ITEMS", "TOMBOLA"), order(read));

		row.setBlocks("not json");
		row.setLayout("not json");
		TicketTemplateDTO broken = service.saved(TicketDocumentType.SALE).get();
		assertEquals(SALE_ORDER, order(broken));
		assertEquals("AIRY", broken.getLayout().get("spacing"));
	}

	@Test
	@DisplayName("Event log: the first save (its values), a change (only what changed: the layout, the switches, the"
			+ " options), a save without change (nothing), a reset (its values of before), a reset without a row (nothing)")
	void events() {
		service.save("SALE", sent(t -> { }), "admin");
		EventLogEntry created = log.only("TICKET_TEMPLATE_SAVED");
		assertEquals("TICKET_TEMPLATE", created.getSubjectType());
		assertEquals("SALE", created.getSubjectId());
		assertTrue(created.getDetails().startsWith("Modèle de ticket « Ticket de vente » créé : Papier 80 mm ; Taille Normale ;"
				+ " Espacement Aérée ; Lignes avant la coupe 0 ; Séparateurs Trait plein ; Ordre En-tête, "), created.getDetails());
		assertTrue(created.getDetails().endsWith("; Options par défaut."), created.getDetails());

		service.save("SALE", sent(t -> {
			t.getLayout().put("spacing", "COMPACT");
			set(t, "INFO", "CASHIER", false);
			element(t, "HEADER", "LOGO").getOptions().put("width", 50);
			element(t, "HEADER", "LOGO").getOptions().put("align", "RIGHT");
		}), "admin");
		assertEquals(2, log.rows.size());
		assertEquals("Modèle de ticket « Ticket de vente » modifié : Espacement : Aérée → Compacte ; Masqués : Adresse,"
				+ " Code article, Code-barres de l'article, Prix unitaire, TVA par taux, Ticket de tombola → Adresse, Caissier,"
				+ " Code article, Code-barres de l'article, Prix unitaire, TVA par taux, Ticket de tombola ; Options : par défaut"
				+ " → Logo (largeur 50 %,"
				+ " position à droite).", log.rows.get(1).getDetails());

		service.save("SALE", service.saved(TicketDocumentType.SALE).get(), "admin");
		assertEquals(2, log.rows.size(), "nothing changed: no event");

		assertTrue(service.reset("SALE"));
		assertTrue(rows.isEmpty());
		assertEquals("TICKET_TEMPLATE_RESET", log.rows.get(2).getCode());
		assertTrue(log.rows.get(2).getDetails().startsWith("Modèle de ticket « Ticket de vente » réinitialisé"));

		assertFalse(service.reset("SALE"));
		assertEquals(3, log.rows.size());
	}

	@Test
	@DisplayName("Part 2: the top text (3 lines, empty), the thank-you (2 lines) and the footer text (6 lines) of 48"
			+ " characters, null = Company Information's; a longer one refused (TICKET_TEMPLATE_TEXT), \\r dropped; the"
			+ " tombola's switch from TOMBOLA_ENABLED and free; the contact in four rows; the passenger code hidden or not")
	void part2() {
		TicketTemplateDTO d = service.defaults(TicketDocumentType.SALE);
		assertEquals(Collections.singletonMap("text", ""), element(d, "TOP_TEXT", "TEXT").getOptions());
		assertEquals(Collections.singletonMap("text", null), element(d, "FOOTER", "THANK_YOU").getOptions());
		assertEquals(Collections.singletonMap("text", null), element(d, "FOOTER", "FOOTER_NOTE").getOptions());
		assertEquals(Collections.singletonMap("passenger", "SHOW"), element(d, "INFO", "CUSTOMER_CODE").getOptions());
		assertEquals(Arrays.asList("THANK_YOU", "WEBSITE", "EMAIL", "PHONE", "FAX", "MF", "FOOTER_NOTE", "SOFTWARE_LABEL"),
				section(d, "FOOTER").getElements().stream().map(ElementDTO::getCode).collect(Collectors.toList()));
		assertFalse(section(d, "TOMBOLA").getOn(), "TOMBOLA_ENABLED absent: off");
		settings.put("TOMBOLA_ENABLED", "true");
		assertTrue(section(service.defaults(TicketDocumentType.SALE), "TOMBOLA").getOn(), "TOMBOLA_ENABLED true: on");
		settings.put("PASSENGER_CUSTOMER", "PASSAGER");
		assertEquals("PASSAGER", service.page().get("passengerCode"));

		String six = "Ligne 1\nLigne 2\nLigne 3\nLigne 4\nLigne 5\n" + new String(new char[48]).replace('\0', 'x');
		TicketTemplateDTO saved = service.save("SALE", sent(t -> {
			element(t, "TOP_TEXT", "TEXT").getOptions().put("text", "Soldes d'hiver\r\njusqu'au 31/01");
			element(t, "FOOTER", "THANK_YOU").getOptions().put("text", "À bientôt !");
			element(t, "FOOTER", "FOOTER_NOTE").getOptions().put("text", six);
			element(t, "INFO", "CUSTOMER_CODE").getOptions().put("passenger", "HIDE");
			set(t, "FOOTER", "FAX", false);
			section(t, "TOMBOLA").setOn(false);
		}), "admin");
		assertEquals("Soldes d'hiver\njusqu'au 31/01", element(saved, "TOP_TEXT", "TEXT").getOptions().get("text"));
		assertEquals(six, element(saved, "FOOTER", "FOOTER_NOTE").getOptions().get("text"));
		assertEquals("HIDE", element(saved, "INFO", "CUSTOMER_CODE").getOptions().get("passenger"));
		assertFalse(on(saved, "FOOTER", "FAX"));
		assertFalse(section(saved, "TOMBOLA").getOn(), "the template decides, TOMBOLA_ENABLED true or not");
		rows.clear();

		assertEquals("TICKET_TEMPLATE_TEXT", refusal(t -> element(t, "TOP_TEXT", "TEXT").getOptions().put("text", "1\n2\n3\n4")));
		assertEquals("TICKET_TEMPLATE_TEXT", refusal(t -> element(t, "FOOTER", "FOOTER_NOTE").getOptions().put("text", six + "\n7")));
		assertEquals("TICKET_TEMPLATE_TEXT", refusal(t -> element(t, "FOOTER", "THANK_YOU").getOptions()
				.put("text", new String(new char[49]).replace('\0', 'x'))));
		assertEquals("TICKET_TEMPLATE_TEXT", refusal(t -> element(t, "FOOTER", "THANK_YOU").getOptions().put("text", 12)));
		assertEquals("TICKET_TEMPLATE_OPTION", refusal(t -> element(t, "INFO", "CUSTOMER_CODE").getOptions().put("passenger", "NO")));
	}

	/** A document's default with a change, saved; the refusal's code when refused (nothing saved). */
	String refusalOf(TicketDocumentType type, Consumer<TicketTemplateDTO> change) {
		TicketTemplateDTO t = service.defaults(type);
		change.accept(t);
		TicketTemplateException e = assertThrows(TicketTemplateException.class, () -> service.save(type.name(), t, "admin"));
		assertTrue(rows.isEmpty(), "nothing saved");
		return e.getCode();
	}

	@Test
	@DisplayName("Step 3: the return voucher and the cash refund slip have their own blocks, the same layout and options;"
			+ " their locked blocks refused off (the voucher's number, original ticket, date, validity, barcode; the"
			+ " refund's number, original ticket, date; both the lines, the VAT, the amount, the MF); a save of each")
	void returnDocuments() {
		TicketTemplateDTO voucher = service.defaults(TicketDocumentType.RETURN_VOUCHER);
		assertEquals(Arrays.asList("HEADER", "TOP_TEXT", "INFO", "ITEMS", "TOTALS", "PAYMENTS", "BARCODE", "FOOTER"),
				order(voucher));
		assertEquals(service.defaults(TicketDocumentType.SALE).getLayout(), voucher.getLayout());
		assertEquals(Arrays.asList("NUMBER", "ORIGINAL_TICKET", "DATE", "VALID_UNTIL", "CASHIER", "CUSTOMER_CODE"),
				section(voucher, "INFO").getElements().stream().map(ElementDTO::getCode).collect(Collectors.toList()));
		assertEquals(Collections.singletonMap("text", null), element(voucher, "FOOTER", "MESSAGE").getOptions());
		assertTrue(element(voucher, "INFO", "CUSTOMER_CODE").getOptions().isEmpty(),
				"no passenger option on a return document");
		TicketTemplateDTO refund = service.defaults(TicketDocumentType.CASH_REFUND);
		assertEquals(Arrays.asList("HEADER", "TOP_TEXT", "INFO", "ITEMS", "TOTALS", "FOOTER"), order(refund));

		for (String[] locked : new String[][] { { "INFO", "NUMBER" }, { "INFO", "ORIGINAL_TICKET" }, { "INFO", "DATE" },
				{ "INFO", "VALID_UNTIL" }, { "ITEMS", "LINES" }, { "ITEMS", "VAT" }, { "TOTALS", "TOTAL" },
				{ "TOTALS", "TOTAL_VAT" }, { "FOOTER", "MF" }, { "HEADER", "STORE_NAME" }, { "HEADER", "DUPLICATE" } }) {
			assertEquals("TICKET_TEMPLATE_LOCKED", refusalOf(TicketDocumentType.RETURN_VOUCHER,
					t -> set(t, locked[0], locked[1], false)), "voucher " + locked[1]);
		}
		assertEquals("TICKET_TEMPLATE_LOCKED", refusalOf(TicketDocumentType.RETURN_VOUCHER, t -> section(t, "BARCODE").setOn(false)));
		for (String[] locked : new String[][] { { "INFO", "NUMBER" }, { "INFO", "ORIGINAL_TICKET" }, { "INFO", "DATE" },
				{ "ITEMS", "VAT" }, { "TOTALS", "TOTAL" }, { "FOOTER", "MF" } }) {
			assertEquals("TICKET_TEMPLATE_LOCKED", refusalOf(TicketDocumentType.CASH_REFUND,
					t -> set(t, locked[0], locked[1], false)), "refund " + locked[1]);
		}
		assertEquals("TICKET_TEMPLATE_UNKNOWN_SECTION", refusalOf(TicketDocumentType.CASH_REFUND,
				t -> t.getSections().add(new SectionDTO("TOMBOLA", true, new ArrayList<>()))));

		TicketTemplateDTO saved = service.save("RETURN_VOUCHER", with(voucher, t -> {
			t.getLayout().put("spacing", "COMPACT");
			set(t, "HEADER", "BADGE", false);
			section(t, "PAYMENTS").setOn(false);
			element(t, "FOOTER", "MESSAGE").getOptions().put("text", "Valable dans tous nos magasins");
		}), "admin");
		assertEquals("COMPACT", saved.getLayout().get("spacing"));
		assertFalse(on(saved, "HEADER", "BADGE"));
		assertFalse(section(saved, "PAYMENTS").getOn());
		assertEquals("Valable dans tous nos magasins", element(saved, "FOOTER", "MESSAGE").getOptions().get("text"));
		assertTrue(rows.containsKey("RETURN_VOUCHER"));
		assertFalse(rows.containsKey("SALE"), "one row per document");
		assertEquals("TICKET_TEMPLATE_SAVED", log.rows.get(log.rows.size() - 1).getCode());
		assertTrue(log.rows.get(log.rows.size() - 1).getDetails().startsWith("Modèle de ticket « Bon de retour » créé"));
		assertTrue(service.reset("RETURN_VOUCHER"));
		assertFalse(rows.containsKey("RETURN_VOUCHER"));
	}

	@Test
	@DisplayName("Step 3 part 2: the warranty ticket (its blocks, the customer layout; locked: the store's name, DUPLICATA,"
			+ " the warranty's number, ticket, item, dates, the item line, the MF; the amounts free) and the tombola slip"
			+ " (one section, no layout: a layout key refused; locked: the section, the sale's number, the member's name);"
			+ " the event log names a block as its document prints it")
	void warrantyAndTombola() {
		TicketTemplateDTO warranty = service.defaults(TicketDocumentType.WARRANTY);
		assertEquals(Arrays.asList("HEADER", "TOP_TEXT", "INFO", "ITEMS", "TOTALS", "BARCODE", "FOOTER"), order(warranty));
		assertEquals(service.defaults(TicketDocumentType.SALE).getLayout(), warranty.getLayout());
		assertEquals(Arrays.asList("NUMBER", "TICKET", "ITEM", "START", "END", "DATE"),
				section(warranty, "INFO").getElements().stream().map(ElementDTO::getCode).collect(Collectors.toList()));
		assertFalse(on(warranty, "ITEMS", "VAT"), "the line's VAT rate: new, off");
		for (String[] locked : new String[][] { { "HEADER", "STORE_NAME" }, { "HEADER", "DUPLICATE" }, { "INFO", "NUMBER" },
				{ "INFO", "TICKET" }, { "INFO", "ITEM" }, { "INFO", "START" }, { "INFO", "END" }, { "ITEMS", "LINES" },
				{ "FOOTER", "MF" } }) {
			assertEquals("TICKET_TEMPLATE_LOCKED", refusalOf(TicketDocumentType.WARRANTY,
					t -> set(t, locked[0], locked[1], false)), "warranty " + locked[1]);
		}
		assertEquals("TICKET_TEMPLATE_LOCKED", refusalOf(TicketDocumentType.WARRANTY, t -> section(t, "INFO").setOn(false)));
		TicketTemplateDTO amountsOff = service.save("WARRANTY", with(warranty, t -> {
			section(t, "ITEMS").setOn(false);
			set(t, "TOTALS", "TOTAL", false);
			set(t, "INFO", "DATE", false);
		}), "admin");
		assertFalse(section(amountsOff, "ITEMS").getOn());
		assertFalse(on(amountsOff, "TOTALS", "TOTAL"));
		assertTrue(log.rows.get(log.rows.size() - 1).getDetails().contains("Article sous garantie"),
				"the warranty's TOTAL named as it prints: " + log.rows.get(log.rows.size() - 1).getDetails());
		rows.clear();

		TicketTemplateDTO slip = service.defaults(TicketDocumentType.TOMBOLA_SLIP);
		assertEquals(Collections.singletonList("SLIP"), order(slip));
		assertTrue(slip.getLayout().isEmpty());
		assertEquals(Arrays.asList("NEW_PAGE", "TITLE", "NUMBER", "BARCODE", "NAME", "PHONE"),
				section(slip, "SLIP").getElements().stream().map(ElementDTO::getCode).collect(Collectors.toList()));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusalOf(TicketDocumentType.TOMBOLA_SLIP, t -> section(t, "SLIP").setOn(false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusalOf(TicketDocumentType.TOMBOLA_SLIP, t -> set(t, "SLIP", "NUMBER", false)));
		assertEquals("TICKET_TEMPLATE_LOCKED", refusalOf(TicketDocumentType.TOMBOLA_SLIP, t -> set(t, "SLIP", "NAME", false)));
		assertEquals("TICKET_TEMPLATE_UNKNOWN_OPTION",
				refusalOf(TicketDocumentType.TOMBOLA_SLIP, t -> t.getLayout().put("paperWidth", 80)));
		TicketTemplateDTO saved = service.save("TOMBOLA_SLIP", with(slip, t -> {
			set(t, "SLIP", "NEW_PAGE", false);
			set(t, "SLIP", "PHONE", false);
		}), "admin");
		assertFalse(on(saved, "SLIP", "NEW_PAGE"));
		String details = log.rows.get(log.rows.size() - 1).getDetails();
		assertTrue(details.startsWith("Modèle de ticket « Ticket de tombola » créé : Ordre Ticket de tombola ;"), details);
		assertFalse(details.contains("Papier"), "no layout lines: " + details);
		assertTrue(details.contains("Sur un ticket à part, Téléphone du membre"), details);
	}

	@Test
	@DisplayName("Step 3 part 2: a block named as its document prints it, else the common name")
	void labelsByDocument() {
		assertEquals("Numéro de bon", TicketBlockLabels.fr(TicketDocumentType.RETURN_VOUCHER, "INFO.NUMBER"));
		assertEquals("Remboursé en espèces", TicketBlockLabels.fr(TicketDocumentType.CASH_REFUND, "TOTALS.TOTAL"));
		assertEquals("N° de garantie", TicketBlockLabels.fr(TicketDocumentType.WARRANTY, "INFO.NUMBER"));
		assertEquals("Numéro de vente", TicketBlockLabels.fr(TicketDocumentType.SALE, "INFO.NUMBER"));
		assertEquals("Caissier", TicketBlockLabels.fr(TicketDocumentType.RETURN_VOUCHER, "INFO.CASHIER"));
		assertEquals("TOTAL", TicketBlockLabels.fr(null, "TOTALS.TOTAL"));
	}

	private static TicketTemplateDTO with(TicketTemplateDTO t, Consumer<TicketTemplateDTO> change) {
		change.accept(t);
		return t;
	}
}
