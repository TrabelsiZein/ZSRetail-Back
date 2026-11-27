package com.digithink.zsretail.core.tickettemplate.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.eventlog.service.AdminEvents;
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.tickettemplate.TicketBlockLabels;
import com.digithink.zsretail.core.tickettemplate.TicketBlocks;
import com.digithink.zsretail.core.tickettemplate.TicketBlocks.Element;
import com.digithink.zsretail.core.tickettemplate.TicketBlocks.Option;
import com.digithink.zsretail.core.tickettemplate.TicketBlocks.Position;
import com.digithink.zsretail.core.tickettemplate.TicketBlocks.Section;
import com.digithink.zsretail.core.tickettemplate.TicketDocumentType;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO.ElementDTO;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateDTO.SectionDTO;
import com.digithink.zsretail.core.tickettemplate.TicketTemplateException;
import com.digithink.zsretail.core.tickettemplate.model.TicketTemplate;
import com.digithink.zsretail.core.tickettemplate.repository.TicketTemplateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.log4j.Log4j2;

/**
 * 3.5.0, ticket templates (docs/modules/ticket-templates.md): the templates of the documents, read by the page
 * "Modèles de tickets" and by the till, saved and reset by the page.
 * <ul>
 * <li>No row = the default: the catalogue's layout, order, switches and options (TicketBlocks), every one today's ticket,
 * the linked elements taken from the old settings as they are now (Company Information, General setup). Those settings
 * are never changed here: the old print code keeps reading them.</li>
 * <li>A save is checked ({@link #normalise} strict): a known document that has a template, each layout option and each
 * element option among its allowed values, known codes once each, no locked block off, the line's VAT off only with
 * "TVA par taux" on. What it lacks takes its default (an element of a later version); the header is put first and the
 * tombola last.</li>
 * <li>A row read back is normalised leniently (a code or an option it does not know is dropped, a wrong value takes its
 * default, a locked block put back on), so a row of another version never breaks a print. A layout of format 1
 * ("compact": true) reads as the spacing COMPACT.</li>
 * <li>A save or a reset is an administrator's gesture in the event log (AdminEvents), when it changed something.</li>
 * </ul>
 */
@Service
@Log4j2
public class TicketTemplateService {

	/** Step 2b: layout and options (format 1: columns, the switches only). */
	public static final int FORMAT_VERSION = 2;
	/** The paper widths, also a POS machine's (PosMachineService). */
	public static final List<Integer> PAPER_WIDTHS = Arrays.asList(58, 80);

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final TypeReference<List<SectionDTO>> SECTIONS = new TypeReference<List<SectionDTO>>() {
	};
	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<Map<String, Object>>() {
	};

	private final TicketTemplateRepository repository;
	private final CompanyInformationService companyInformationService;
	private final GeneralSetupService generalSetupService;
	private final AdminEvents adminEvents;

	public TicketTemplateService(TicketTemplateRepository repository, CompanyInformationService companyInformationService,
			GeneralSetupService generalSetupService, AdminEvents adminEvents) {
		this.repository = repository;
		this.companyInformationService = companyInformationService;
		this.generalSetupService = generalSetupService;
		this.adminEvents = adminEvents;
	}

	// ─── Reads ───────────────────────────────────────────────────────

	/**
	 * The page's data and the till's: per document its type, whether it has a template yet, its catalogue (layout
	 * options, sections), its saved template (null: none, the default prints) and its default; the linked settings' values.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> page() {
		Map<String, Boolean> linked = linkedValues();
		List<Map<String, Object>> documents = new ArrayList<>();
		for (TicketDocumentType type : TicketDocumentType.values()) {
			Map<String, Object> document = new LinkedHashMap<>();
			document.put("type", type.name());
			document.put("available", type.isAvailable());
			if (type.isAvailable()) {
				document.put("internal", type.isInternal()); // step 4: a layout only, no blocks
				document.put("layout", TicketBlocks.layout(type));
				document.put("sections", TicketBlocks.of(type));
				document.put("template", saved(type).orElse(null));
				document.put("defaults", defaults(type, linked));
			}
			documents.add(document);
		}
		Map<String, Object> page = new LinkedHashMap<>();
		page.put("documents", documents);
		page.put("formatVersion", FORMAT_VERSION);
		page.put("linked", linked);
		// step 2b: the passenger's customer code (General setup PASSENGER_CUSTOMER), for "hide the passenger code"
		page.put("passengerCode", generalSetupService.findValueByCode("PASSENGER_CUSTOMER"));
		return page;
	}

	/** The saved template of a document, normalised; empty when it has none (the default prints). */
	@Transactional(readOnly = true)
	public Optional<TicketTemplateDTO> saved(TicketDocumentType type) {
		return repository.findByDocumentType(type.name()).map(row -> toDTO(type, row));
	}

	/** The default of a document: the catalogue's, the linked elements from the old settings' values now. */
	public TicketTemplateDTO defaults(TicketDocumentType type) {
		return defaults(type, linkedValues());
	}

	private TicketTemplateDTO defaults(TicketDocumentType type, Map<String, Boolean> linked) {
		TicketTemplateDTO dto = new TicketTemplateDTO();
		dto.setDocumentType(type.name());
		dto.setFormatVersion(FORMAT_VERSION);
		dto.setOrigin("LOCAL");
		for (Option option : TicketBlocks.layout(type)) {
			dto.getLayout().put(option.getKey(), option.getDefaultValue());
		}
		for (Section section : TicketBlocks.of(type)) {
			List<ElementDTO> elements = new ArrayList<>();
			for (Element e : section.getElements()) {
				Map<String, Object> options = new LinkedHashMap<>();
				e.getOptions().forEach(option -> options.put(option.getKey(), option.getDefaultValue()));
				elements.add(new ElementDTO(e.getCode(), e.getLinked() != null ? linked.get(e.getLinked()) : e.isDefaultOn(),
						options));
			}
			boolean on = section.getLinked() != null ? Boolean.TRUE.equals(linked.get(section.getLinked()))
					: section.isDefaultOn();
			dto.getSections().add(new SectionDTO(section.getCode(), on, elements));
		}
		return dto;
	}

	/**
	 * The old settings that the linked elements replace, as the old code reads them: the address printed only when
	 * switched on (null = no), the thank-you line and the ZS Retail label unless switched off (null = yes), the loyalty
	 * rows unless their General setup row says false (absent = yes, as SalesHeaderAPI sends them).
	 */
	Map<String, Boolean> linkedValues() {
		CompanyInformationDTO company = companyInformationService.get();
		Map<String, Boolean> linked = new LinkedHashMap<>();
		linked.put(TicketBlocks.LINK_PRINT_ADDRESS, company != null && Boolean.TRUE.equals(company.getReceiptPrintAddress()));
		linked.put(TicketBlocks.LINK_THANK_YOU, company == null || !Boolean.FALSE.equals(company.getReceiptShowThankYou()));
		linked.put(TicketBlocks.LINK_SOFTWARE_LABEL,
				company == null || !Boolean.FALSE.equals(company.getReceiptShowSoftwareLabel()));
		linked.put(TicketBlocks.LINK_LOYALTY_EARNED, settingOn("TICKET_SHOW_LOYALTY_EARNED"));
		linked.put(TicketBlocks.LINK_LOYALTY_BALANCE, settingOn("TICKET_SHOW_LOYALTY_BALANCE"));
		// step 2b: the tombola slip, "true" only (absent or anything else = no, as GET /config reads it)
		linked.put(TicketBlocks.LINK_TOMBOLA, "true".equalsIgnoreCase(generalSetupService.findValueByCode("TOMBOLA_ENABLED")));
		return linked;
	}

	private boolean settingOn(String code) {
		String value = generalSetupService.findValueByCode(code);
		return value == null || Boolean.parseBoolean(value);
	}

	// ─── Writes ──────────────────────────────────────────────────────

	/** Saves a document's template (checked, see the class); its normalised form. */
	@Transactional
	public TicketTemplateDTO save(String typeName, TicketTemplateDTO sent, String by) {
		TicketDocumentType type = availableType(typeName);
		TicketTemplateDTO clean = normalise(type, sent, true);
		Optional<TicketTemplate> existing = repository.findByDocumentType(type.name());
		Map<String, String> before = existing.map(row -> describe(toDTO(type, row))).orElseGet(LinkedHashMap::new);
		TicketTemplate row = existing.orElseGet(() -> {
			TicketTemplate created = new TicketTemplate();
			created.setDocumentType(type.name());
			created.setCreatedBy(by);
			created.setOrigin("LOCAL");
			return created;
		});
		row.setLayout(write(clean.getLayout()));
		row.setBlocks(write(clean.getSections()));
		row.setFormatVersion(FORMAT_VERSION);
		row.setUpdatedBy(by);
		row.setActive(true);
		TicketTemplate stored = repository.save(row);
		TicketTemplateDTO result = toDTO(type, stored);
		adminEvents.ticketTemplateSaved(type.name(), TicketBlockLabels.fr(type.name()), before, describe(result));
		return result;
	}

	/** "Réinitialiser": deletes the document's row, the default prints again; false when it had none. */
	@Transactional
	public boolean reset(String typeName) {
		TicketDocumentType type = availableType(typeName);
		Optional<TicketTemplate> existing = repository.findByDocumentType(type.name());
		if (!existing.isPresent()) {
			return false;
		}
		Map<String, String> before = describe(toDTO(type, existing.get()));
		repository.delete(existing.get());
		adminEvents.ticketTemplateReset(type.name(), TicketBlockLabels.fr(type.name()), before);
		return true;
	}

	private static TicketDocumentType availableType(String typeName) {
		TicketDocumentType type = TicketDocumentType.of(typeName);
		if (type == null) {
			throw new TicketTemplateException(404, "TICKET_TEMPLATE_TYPE_UNKNOWN", "Unknown document type: " + typeName,
					"type", typeName);
		}
		if (!type.isAvailable()) {
			throw TicketTemplateException.refused("TICKET_TEMPLATE_NOT_AVAILABLE",
					"The document " + type.name() + " has no template yet", "type", type.name());
		}
		return type;
	}

	// ─── Normalising, checking ───────────────────────────────────────

	/**
	 * A template in its full form: every layout option; every section of the catalogue once, the header first and the
	 * tombola last, the others in the order sent (a section not sent goes after the one it follows in the catalogue);
	 * every element in the catalogue's order with every one of its options. Strict (a save): anything wrong is refused.
	 * Lenient (a row read back): what is unknown is dropped, a wrong value takes its default, a locked block is put back
	 * on.
	 */
	static TicketTemplateDTO normalise(TicketDocumentType type, TicketTemplateDTO sent, boolean strict) {
		TicketTemplateDTO in = sent != null ? sent : new TicketTemplateDTO();
		// step 4: an internal ticket has no blocks: a save that sends some is refused (a row read back drops them)
		if (strict && type.isInternal() && in.getSections() != null && !in.getSections().isEmpty()) {
			throw TicketTemplateException.refused("TICKET_TEMPLATE_NO_BLOCKS",
					"The document " + type.name() + " has no blocks: its layout only", "type", type.name());
		}
		TicketTemplateDTO out = new TicketTemplateDTO();
		out.setDocumentType(type.name());
		out.setFormatVersion(FORMAT_VERSION);
		out.setOrigin(in.getOrigin() != null ? in.getOrigin() : "LOCAL");
		out.setUpdatedAt(in.getUpdatedAt());
		out.setUpdatedBy(in.getUpdatedBy());

		Map<String, Object> layoutIn = in.getLayout() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(in.getLayout());
		if (!strict && Boolean.TRUE.equals(layoutIn.remove("compact")) && !layoutIn.containsKey("spacing")) {
			layoutIn.put("spacing", "COMPACT"); // format 1
		}
		out.setLayout(options(TicketBlocks.layout(type), layoutIn, strict, null, null));

		List<Section> catalogue = TicketBlocks.of(type);
		// the sections sent, known, once each, in their order
		List<String> order = new ArrayList<>();
		Map<String, SectionDTO> given = new LinkedHashMap<>();
		for (SectionDTO section : in.getSections() == null ? new ArrayList<SectionDTO>() : in.getSections()) {
			String code = section == null ? null : section.getCode();
			if (TicketBlocks.section(type, code) == null) {
				if (strict) {
					throw TicketTemplateException.refused("TICKET_TEMPLATE_UNKNOWN_SECTION", "Unknown section: " + code,
							"section", code);
				}
				continue;
			}
			if (given.containsKey(code)) {
				if (strict) {
					throw TicketTemplateException.refused("TICKET_TEMPLATE_DUPLICATE", "Section sent twice: " + code,
							"section", code, "element", null);
				}
				continue;
			}
			given.put(code, section);
			order.add(code);
		}
		// a section not sent: after the one before it in the catalogue (or first)
		for (int i = 0; i < catalogue.size(); i++) {
			String code = catalogue.get(i).getCode();
			if (order.contains(code)) {
				continue;
			}
			int at = 0;
			for (int j = i - 1; j >= 0; j--) {
				int previous = order.indexOf(catalogue.get(j).getCode());
				if (previous >= 0) {
					at = previous + 1;
					break;
				}
			}
			order.add(at, code);
		}
		// the header first, the tombola last
		for (Section section : catalogue) {
			if (section.getPosition() == Position.FIRST) {
				order.remove(section.getCode());
				order.add(0, section.getCode());
			} else if (section.getPosition() == Position.LAST) {
				order.remove(section.getCode());
				order.add(section.getCode());
			}
		}

		for (String code : order) {
			Section section = TicketBlocks.section(type, code);
			SectionDTO sentSection = given.get(code);
			boolean on = sentSection == null || sentSection.getOn() == null ? section.isDefaultOn() : sentSection.getOn();
			if (!on && section.getLock() != null) {
				if (strict) {
					throw TicketTemplateException.refused("TICKET_TEMPLATE_LOCKED", "The section " + code + " cannot be off",
							"section", code, "element", null);
				}
				on = true;
			}
			out.getSections().add(new SectionDTO(code, on, elements(section, sentSection, strict)));
		}

		// the line's VAT off only with the VAT by rate on
		for (Section section : catalogue) {
			for (Element element : section.getElements()) {
				if (element.getOffNeeds() == null) {
					continue;
				}
				ElementDTO value = find(find(out, section.getCode()), element.getCode());
				SectionDTO needed = find(out, element.getOffNeeds());
				boolean neededOn = needed != null && Boolean.TRUE.equals(needed.getOn());
				if (value != null && !Boolean.TRUE.equals(value.getOn()) && !neededOn) {
					if (strict) {
						throw TicketTemplateException.refused("TICKET_TEMPLATE_VAT_NEEDED",
								"The VAT of the lines can be off only with the VAT by rate on", "section", section.getCode(),
								"element", element.getCode());
					}
					value.setOn(true);
				}
			}
		}
		return out;
	}

	/**
	 * Every option of a list, in its order: the value sent when allowed, else its default (lenient) or a refusal
	 * (strict: TICKET_TEMPLATE_OPTION, a key it does not know TICKET_TEMPLATE_UNKNOWN_OPTION). A number sent as 58.0 by a
	 * JSON reader is taken as 58.
	 */
	private static Map<String, Object> options(List<Option> catalogue, Map<String, Object> sent, boolean strict,
			String section, String element) {
		Map<String, Object> given = sent == null ? new LinkedHashMap<>() : sent;
		if (strict) {
			for (String key : given.keySet()) {
				if (catalogue.stream().noneMatch(o -> o.getKey().equals(key))) {
					throw TicketTemplateException.refused("TICKET_TEMPLATE_UNKNOWN_OPTION", "Unknown option: " + key,
							"section", section, "element", element, "option", key);
				}
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		for (Option option : catalogue) {
			if (!given.containsKey(option.getKey())) {
				out.put(option.getKey(), option.getDefaultValue());
				continue;
			}
			Object value = whole(given.get(option.getKey()));
			if (value instanceof String && option.getMaxLines() != null) {
				value = ((String) value).replace("\r", ""); // a text's lines: \n only
			}
			if (!option.accepts(value)) {
				if (strict && option.getMaxLines() != null) {
					throw TicketTemplateException.refused("TICKET_TEMPLATE_TEXT",
							"Text " + option.getKey() + ": at most " + option.getMaxLines() + " lines of "
									+ option.getMaxLength() + " characters",
							"section", section, "element", element, "maxLines", option.getMaxLines(), "maxLength",
							option.getMaxLength());
				}
				if (strict) {
					throw TicketTemplateException.refused("TICKET_TEMPLATE_OPTION",
							"Option " + option.getKey() + ": " + value + " is not allowed", "section", section, "element",
							element, "option", option.getKey(), "value", value);
				}
				value = option.getDefaultValue();
			}
			out.put(option.getKey(), value);
		}
		return out;
	}

	/** A whole number as an Integer (a JSON reader may give a Long or 58.0); anything else as it is. */
	private static Object whole(Object value) {
		if (value instanceof Number && !(value instanceof Integer)) {
			double d = ((Number) value).doubleValue();
			if (d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) {
				return (int) d;
			}
		}
		return value;
	}

	private static List<ElementDTO> elements(Section section, SectionDTO sent, boolean strict) {
		Map<String, ElementDTO> given = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		for (ElementDTO element : sent == null || sent.getElements() == null ? new ArrayList<ElementDTO>()
				: sent.getElements()) {
			String code = element == null ? null : element.getCode();
			if (section.element(code) == null) {
				if (strict) {
					throw TicketTemplateException.refused("TICKET_TEMPLATE_UNKNOWN_ELEMENT",
							"Unknown element " + code + " in " + section.getCode(), "section", section.getCode(), "element",
							code);
				}
				continue;
			}
			if (!seen.add(code)) {
				if (strict) {
					throw TicketTemplateException.refused("TICKET_TEMPLATE_DUPLICATE", "Element sent twice: " + code,
							"section", section.getCode(), "element", code);
				}
				continue;
			}
			given.put(code, element);
		}
		List<ElementDTO> out = new ArrayList<>();
		for (Element element : section.getElements()) {
			ElementDTO value = given.get(element.getCode());
			boolean on = value == null || value.getOn() == null ? element.isDefaultOn() : value.getOn();
			if (!on && element.getLock() != null) {
				if (strict) {
					throw TicketTemplateException.refused("TICKET_TEMPLATE_LOCKED",
							"The element " + element.getCode() + " of " + section.getCode() + " cannot be off", "section",
							section.getCode(), "element", element.getCode());
				}
				on = true;
			}
			out.add(new ElementDTO(element.getCode(), on, options(element.getOptions(),
					value == null ? null : value.getOptions(), strict, section.getCode(), element.getCode())));
		}
		return out;
	}

	private static SectionDTO find(TicketTemplateDTO template, String code) {
		for (SectionDTO section : template.getSections()) {
			if (section.getCode().equals(code)) {
				return section;
			}
		}
		return null;
	}

	private static ElementDTO find(SectionDTO section, String code) {
		if (section == null) {
			return null;
		}
		for (ElementDTO element : section.getElements()) {
			if (element.getCode().equals(code)) {
				return element;
			}
		}
		return null;
	}

	// ─── The row ─────────────────────────────────────────────────────

	private static TicketTemplateDTO toDTO(TicketDocumentType type, TicketTemplate row) {
		TicketTemplateDTO dto = new TicketTemplateDTO();
		dto.setLayout(readLayout(row.getLayout()));
		dto.setSections(readSections(row.getBlocks()));
		dto.setOrigin(row.getOrigin());
		dto.setUpdatedAt(row.getUpdatedAt());
		dto.setUpdatedBy(row.getUpdatedBy());
		return normalise(type, dto, false);
	}

	private static Map<String, Object> readLayout(String layout) {
		if (layout == null || layout.trim().isEmpty()) {
			return new LinkedHashMap<>();
		}
		try {
			return JSON.readValue(layout, MAP);
		} catch (JsonProcessingException e) {
			log.warn("Ticket template: layout not readable, the default is used ({})", e.getOriginalMessage());
			return new LinkedHashMap<>();
		}
	}

	private static List<SectionDTO> readSections(String blocks) {
		if (blocks == null || blocks.trim().isEmpty()) {
			return new ArrayList<>();
		}
		try {
			return JSON.readValue(blocks, SECTIONS);
		} catch (JsonProcessingException e) {
			log.warn("Ticket template: blocks not readable, the default order is used ({})", e.getOriginalMessage());
			return new ArrayList<>();
		}
	}

	private static String write(Object value) {
		try {
			return JSON.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Ticket template: not written", e);
		}
	}

	/**
	 * A template in words, for the event log: Papier, Taille, Espacement, Lignes avant la coupe, Séparateurs, Ordre (the
	 * sections), Masqués (the sections and elements off), Options (each element option not at its default).
	 */
	static Map<String, String> describe(TicketTemplateDTO t) {
		Map<String, String> m = new LinkedHashMap<>();
		Map<String, Object> layout = t.getLayout();
		TicketDocumentType type = TicketDocumentType.of(t.getDocumentType());
		// step 3 part 2: a document without layout (the tombola slip: the sale ticket's) has no layout lines; step 4: an
		// internal ticket its own (no blank lines, no separators, the rows at zero), and no blocks (no order, no switches)
		if (layout != null && !layout.isEmpty()) {
			m.put("Papier", layout.get("paperWidth") + " mm");
			m.put("Taille", TicketBlockLabels.fr(String.valueOf(layout.get("fontSize"))));
			m.put("Espacement", TicketBlockLabels.fr("spacing." + layout.get("spacing")));
			if (layout.containsKey("feedLines")) {
				m.put("Lignes avant la coupe", String.valueOf(layout.get("feedLines")));
			}
			if (layout.containsKey("separator")) {
				m.put("Séparateurs", TicketBlockLabels.fr("separator." + layout.get("separator")));
			}
			if (layout.containsKey("hideZeroRows")) {
				m.put("Lignes à zéro", TicketBlockLabels.fr("hideZeroRows." + layout.get("hideZeroRows")));
			}
		}
		if (t.getSections().isEmpty()) {
			return m;
		}
		m.put("Ordre", t.getSections().stream().map(s -> TicketBlockLabels.fr(type, s.getCode())).collect(Collectors.joining(", ")));
		List<String> off = new ArrayList<>();
		List<String> options = new ArrayList<>();
		for (SectionDTO section : t.getSections()) {
			if (!Boolean.TRUE.equals(section.getOn())) {
				off.add(TicketBlockLabels.fr(type, section.getCode()));
			}
			Section catalogue = type == null ? null : TicketBlocks.section(type, section.getCode());
			for (ElementDTO element : section.getElements()) {
				String name = TicketBlockLabels.fr(type, section.getCode() + "." + element.getCode());
				if (Boolean.TRUE.equals(section.getOn()) && !Boolean.TRUE.equals(element.getOn())) {
					off.add(name);
				}
				Element definition = catalogue == null ? null : catalogue.element(element.getCode());
				if (definition == null || element.getOptions() == null) {
					continue;
				}
				List<String> changed = new ArrayList<>();
				for (Option option : definition.getOptions()) {
					Object value = element.getOptions().get(option.getKey());
					if (value != null && !value.equals(option.getDefaultValue()) && !"".equals(value)) {
						changed.add(TicketBlockLabels.fr("option." + option.getKey()) + " " + TicketBlockLabels.value(option.getKey(), value));
					}
				}
				if (!changed.isEmpty()) {
					options.add(name + " (" + String.join(", ", changed) + ")");
				}
			}
		}
		m.put("Masqués", off.isEmpty() ? "aucun" : String.join(", ", off));
		m.put("Options", options.isEmpty() ? "par défaut" : String.join(", ", options));
		return m;
	}
}
