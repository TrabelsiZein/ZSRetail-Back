package com.digithink.zsretail.core.tickettemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 3.5.0, ticket templates: the block catalogue, the back end's (docs/modules/ticket-templates.md, "The blocks"). A
 * document's template is its <b>layout</b> (paper width, size, spacing, blank lines before the cut, separator style) and
 * its <b>sections</b>, in print order (moved up and down on the page), and inside each section its <b>elements</b>,
 * switched on or off (their order is the section's), some with <b>options</b> (step 2b: the logo's width, height and
 * position, a size, the item name wrapped or cut). The header always prints first and the tombola slip last
 * ({@link Position}).
 * <ul>
 * <li>A locked section or element ({@link Lock}) cannot be switched off: the save is refused.</li>
 * <li>The VAT row of each item ({@code ITEMS.VAT}) may be off only while the section {@code VAT_BY_RATE} is on (the
 * rates stay on the ticket): {@link Element#getOffNeeds}.</li>
 * <li>Step 6: every default is the built-in one (no element takes an old setting any more): the address off, the
 * thank-you line, the "ZS Retail" label and the loyalty rows on, the tombola slip's section on (TOMBOLA_ENABLED alone
 * decides, sale-ticket.js). A store's old values were carried into its templates at the upgrade (db/3.5.0/update.sql).</li>
 * <li>Every default is today's ticket: an option's default ({@link Option#getDefaultValue}, null for the logo's width and
 * height: today's 100 px and 40 px) prints what the old code prints.</li>
 * </ul>
 * The front renders the codes (src/libs/ticket/sale-template.js holds the same default, proved by
 * tools/ticket-render-check.mjs against the old ticket); the page names them in the screen's language.
 */
public final class TicketBlocks {

	/** Why a block cannot be switched off. */
	public enum Lock {
		/** A mention a ticket must carry (Zein confirms the list with his accountant). */
		LEGAL,
		/** Needed by the store (the payments of a sale). */
		FUNCTIONAL
	}

	/** A section that does not move. */
	public enum Position {
		FIRST, LAST
	}

	/**
	 * Step 2b: an option of the layout or of an element. A choice among {@link #getValues} (texts or numbers), a whole
	 * number from {@link #getMin} to {@link #getMax}, or a text of at most {@link #getMaxLines} lines of
	 * {@link #getMaxLength} characters; {@link #isNullable}: null is allowed and means today's value (a text: the one
	 * Company Information gives today).
	 */
	@Getter
	@AllArgsConstructor
	public static final class Option {
		private final String key;
		private final Object defaultValue;
		private final List<Object> values;
		private final Integer min;
		private final Integer max;
		private final boolean nullable;
		private final Integer maxLines;
		private final Integer maxLength;

		/** Whether a value is allowed (a number given as 58.0 is not; a text by its lines and their length). */
		public boolean accepts(Object value) {
			if (value == null) {
				return nullable;
			}
			if (maxLines != null) {
				if (!(value instanceof String)) {
					return false;
				}
				String[] lines = ((String) value).split("\n", -1);
				if (lines.length > maxLines) {
					return false;
				}
				for (String line : lines) {
					if (line.length() > maxLength) {
						return false;
					}
				}
				return true;
			}
			if (values != null) {
				return values.contains(value);
			}
			return value instanceof Integer && (Integer) value >= min && (Integer) value <= max;
		}
	}

	/** An element of a section: a switch, with its options (step 2b). */
	@Getter
	@AllArgsConstructor
	public static final class Element {
		private final String code;
		private final boolean defaultOn;
		/** Null: it can be switched off. */
		private final Lock lock;
		/** The section that must be on for this element to be off (null: none). */
		private final String offNeeds;
		private final List<Option> options;

		Element with(Option... more) {
			return new Element(code, defaultOn, lock, offNeeds, Collections.unmodifiableList(Arrays.asList(more)));
		}

		public Option option(String key) {
			for (Option option : options) {
				if (option.getKey().equals(key)) {
					return option;
				}
			}
			return null;
		}
	}

	/** A section of a document: moved up and down, and switched off when it is not locked. */
	@Getter
	@AllArgsConstructor
	public static final class Section {
		private final String code;
		private final boolean defaultOn;
		private final Lock lock;
		private final Position position;
		/** Switching it off is allowed but the page warns (the sale's barcode: the returns scan it). */
		private final boolean warnOff;
		private final List<Element> elements;

		public Element element(String elementCode) {
			for (Element element : elements) {
				if (element.getCode().equals(elementCode)) {
					return element;
				}
			}
			return null;
		}
	}

	public static final String VAT_BY_RATE = "VAT_BY_RATE";

	private static Element on(String code) {
		return new Element(code, true, null, null, Collections.emptyList());
	}

	private static Element off(String code) {
		return new Element(code, false, null, null, Collections.emptyList());
	}

	private static Element locked(String code, Lock lock) {
		return new Element(code, true, lock, null, Collections.emptyList());
	}

	/** A choice: the default first, then every value. */
	private static Option choice(String key, Object defaultValue, Object... values) {
		return new Option(key, defaultValue, Collections.unmodifiableList(Arrays.asList(values)), null, null, false, null,
				null);
	}

	/** A whole number from min to max, or null (today's value) when its default is null. */
	private static Option number(String key, Integer defaultValue, int min, int max) {
		return new Option(key, defaultValue, null, min, max, defaultValue == null, null, null);
	}

	/**
	 * Step 2b: a text of at most maxLines lines of maxLength characters (48: a line at 80 mm; the page warns above 32, a
	 * line at 58 mm, and the ticket wraps, never cuts); null allowed: the built-in text (step 6 part 1: the thank-you line
	 * "Merci pour votre achat !", no footer text; Company Information's texts were carried at the upgrade). Only a save
	 * checks the limits: a text read longer is kept (a carried one), the page asks to shorten it before the next save.
	 */
	private static Option text(String key, String defaultValue, int maxLines, int maxLength) {
		return new Option(key, defaultValue, null, null, null, true, maxLines, maxLength);
	}

	private static Section section(String code, boolean defaultOn, Lock lock, Position position, boolean warnOff,
			Element... elements) {
		return new Section(code, defaultOn, lock, position, warnOff,
				Collections.unmodifiableList(Arrays.asList(elements)));
	}

	/**
	 * Step 2b: the layout of a customer ticket. Paper 58 / 80 (80); text size SMALL / NORMAL / LARGE (NORMAL, 12 px);
	 * spacing AIRY (today's) / NORMAL (about half the gaps) / COMPACT; blank lines added before the cut, 0 to 6 (0);
	 * separators LINE (today's ━ and ═) / DASHES / NONE.
	 */
	private static final List<Option> CUSTOMER_LAYOUT = Collections.unmodifiableList(Arrays.asList(
			choice("paperWidth", 80, 58, 80),
			choice("fontSize", "NORMAL", "SMALL", "NORMAL", "LARGE"),
			choice("spacing", "AIRY", "AIRY", "NORMAL", "COMPACT"),
			number("feedLines", 0, 0, 6),
			choice("separator", "LINE", "LINE", "DASHES", "NONE")));

	/**
	 * Step 4: the layout of an internal ticket (the session and store tickets X / Z, the slips, the closing ticket): the
	 * customer tickets' paper, text size and spacing (the same values, AIRY today's), and one switch: the rows at zero
	 * hidden (false, today's: every row printed). No blank lines, no separators option, no blocks.
	 */
	private static final List<Option> INTERNAL_LAYOUT = Collections.unmodifiableList(Arrays.asList(
			choice("paperWidth", 80, 58, 80),
			choice("fontSize", "NORMAL", "SMALL", "NORMAL", "LARGE"),
			choice("spacing", "AIRY", "AIRY", "NORMAL", "COMPACT"),
			choice("hideZeroRows", false, false, true)));

	/** A size level of one element: MEDIUM is today's. */
	private static Option size() {
		return choice("size", "MEDIUM", "SMALL", "MEDIUM", "LARGE");
	}

	/**
	 * The sale ticket (its split bill, its duplicate, the tombola slip), in the order it prints today. Not locked, to check
	 * with the accountant: the address, the unit price. Step 2b: the logo's width (20 to 100 % of the printable width;
	 * null: today's 100 px at most), its maximum height (5 to 60 mm; null: today's 40 px) and position; the store name's
	 * and the TOTAL's size; the item name wrapped (today) or cut on one line; the item's code, its barcode number and the
	 * promotion's name in the discount row (today). Part 2: the top text (3 lines, empty), the thank-you sentence (2
	 * lines) and the footer text (6 lines) owned by the template (null: Company Information's, as today); the contact
	 * split into its four rows; the passenger's customer code hidden or not; the tombola slip's switch (from
	 * TOMBOLA_ENABLED).
	 */
	private static final List<Section> SALE = Collections.unmodifiableList(Arrays.asList(
			section("HEADER", true, Lock.LEGAL, Position.FIRST, false,
					on("LOGO").with(number("width", null, 20, 100), number("maxHeight", null, 5, 60),
							choice("align", "CENTER", "LEFT", "CENTER", "RIGHT")),
					locked("STORE_NAME", Lock.LEGAL).with(size()), off("ADDRESS"), on("TITLE"),
					locked("DUPLICATE", Lock.LEGAL)),
			section("TOP_TEXT", true, null, null, false,
					on("TEXT").with(text("text", "", 3, 48))),
			section("INFO", true, Lock.LEGAL, null, false,
					locked("NUMBER", Lock.LEGAL), locked("DATE", Lock.LEGAL), on("CASHIER"),
					on("CUSTOMER_CODE").with(choice("passenger", "SHOW", "SHOW", "HIDE"))),
			section("ITEMS", true, Lock.LEGAL, null, false,
					locked("LINES", Lock.LEGAL).with(choice("name", "WRAP", "WRAP", "CUT")), off("ITEM_CODE"),
					off("ITEM_BARCODE"), off("UNIT_PRICE"), on("DISCOUNT"), on("PROMOTION_NAME"),
					new Element("VAT", true, null, VAT_BY_RATE, Collections.emptyList())),
			section("TOTALS", true, Lock.LEGAL, null, false,
					on("TICKET_DISCOUNT"), locked("TOTAL_VAT", Lock.LEGAL), locked("TOTAL", Lock.LEGAL).with(size())),
			section(VAT_BY_RATE, false, null, null, false),
			section("PAYMENTS", true, Lock.FUNCTIONAL, null, false),
			section("LOYALTY", true, null, null, false,
					on("CARD"), on("EARNED"), on("USED"), on("BALANCE")),
			section("BARCODE", true, null, null, true),
			section("FOOTER", true, Lock.LEGAL, null, false,
					on("THANK_YOU").with(text("text", null, 2, 48)), on("WEBSITE"), on("EMAIL"),
					on("PHONE"), on("FAX"), locked("MF", Lock.LEGAL), on("FOOTER_NOTE").with(text("text", null, 6, 48)),
					on("SOFTWARE_LABEL")),
			section("TOMBOLA", true, null, Position.LAST, false)));

	/**
	 * Step 3: the return voucher (its first print at the till and its reprint), in the order it prints today: the item
	 * lines as the sale's (no VAT by rate: the line's VAT locked), the TOTAL its amount, the footer with its own message
	 * (`text` 2 lines, null = today's) in place of the thank-you and its validity line. Locked:
	 * the store's name, the voucher's number, the original ticket, the date, the validity date, the lines, the VAT, the
	 * amount, the MF, DUPLICATA on a reprint, the barcode (the till scans it to take the voucher).
	 */
	private static final List<Section> RETURN_VOUCHER = Collections.unmodifiableList(Arrays.asList(
			section("HEADER", true, Lock.LEGAL, Position.FIRST, false,
					on("LOGO").with(number("width", null, 20, 100), number("maxHeight", null, 5, 60),
							choice("align", "CENTER", "LEFT", "CENTER", "RIGHT")),
					locked("STORE_NAME", Lock.LEGAL).with(size()), off("ADDRESS"), on("TITLE"),
					locked("DUPLICATE", Lock.LEGAL), on("BADGE")),
			section("TOP_TEXT", true, null, null, false,
					on("TEXT").with(text("text", "", 3, 48))),
			section("INFO", true, Lock.LEGAL, null, false,
					locked("NUMBER", Lock.LEGAL), locked("ORIGINAL_TICKET", Lock.LEGAL), locked("DATE", Lock.LEGAL),
					locked("VALID_UNTIL", Lock.FUNCTIONAL), on("CASHIER"), on("CUSTOMER_CODE")),
			section("ITEMS", true, Lock.LEGAL, null, false,
					locked("LINES", Lock.LEGAL).with(choice("name", "WRAP", "WRAP", "CUT")), off("ITEM_CODE"),
					off("ITEM_BARCODE"), off("UNIT_PRICE"), on("DISCOUNT"), on("PROMOTION_NAME"), locked("VAT", Lock.LEGAL)),
			section("TOTALS", true, Lock.LEGAL, null, false,
					off("TICKET_DISCOUNT"), locked("TOTAL_VAT", Lock.LEGAL), locked("TOTAL", Lock.LEGAL).with(size())),
			section("PAYMENTS", true, null, null, false),
			section("BARCODE", true, Lock.FUNCTIONAL, null, false),
			section("FOOTER", true, Lock.LEGAL, null, false,
					on("MESSAGE").with(text("text", null, 2, 48)), on("VALIDITY"), on("WEBSITE"), on("EMAIL"), on("PHONE"),
					on("FAX"), locked("MF", Lock.LEGAL), on("FOOTER_NOTE").with(text("text", null, 6, 48)),
					on("SOFTWARE_LABEL"))));

	/**
	 * Step 3: the cash refund slip (a return refunded in cash), in the order it prints today: no payment, no barcode, no
	 * duplicate. Locked: the store's name, the return's number, the original ticket, the date, the lines, the VAT, the
	 * amount refunded, the MF.
	 */
	private static final List<Section> CASH_REFUND = Collections.unmodifiableList(Arrays.asList(
			section("HEADER", true, Lock.LEGAL, Position.FIRST, false,
					on("LOGO").with(number("width", null, 20, 100), number("maxHeight", null, 5, 60),
							choice("align", "CENTER", "LEFT", "CENTER", "RIGHT")),
					locked("STORE_NAME", Lock.LEGAL).with(size()), off("ADDRESS"), on("TITLE"),
					on("BADGE")),
			section("TOP_TEXT", true, null, null, false,
					on("TEXT").with(text("text", "", 3, 48))),
			section("INFO", true, Lock.LEGAL, null, false,
					locked("NUMBER", Lock.LEGAL), locked("ORIGINAL_TICKET", Lock.LEGAL), locked("DATE", Lock.LEGAL),
					on("CASHIER"), on("CUSTOMER_CODE")),
			section("ITEMS", true, Lock.LEGAL, null, false,
					locked("LINES", Lock.LEGAL).with(choice("name", "WRAP", "WRAP", "CUT")), off("ITEM_CODE"),
					off("ITEM_BARCODE"), off("UNIT_PRICE"), on("DISCOUNT"), on("PROMOTION_NAME"), locked("VAT", Lock.LEGAL)),
			section("TOTALS", true, Lock.LEGAL, null, false,
					on("TICKET_DISCOUNT"), locked("TOTAL_VAT", Lock.LEGAL), locked("TOTAL", Lock.LEGAL).with(size())),
			section("FOOTER", true, Lock.LEGAL, null, false,
					on("MESSAGE").with(text("text", null, 2, 48)), on("WEBSITE"), on("EMAIL"), on("PHONE"), on("FAX"),
					locked("MF", Lock.LEGAL), on("FOOTER_NOTE").with(text("text", null, 6, 48)),
					on("SOFTWARE_LABEL"))));

	/**
	 * Step 3 part 2: the warranty ticket and its duplicate, in the order it prints today: the title GARANTIE, DUPLICATA on
	 * the duplicate, the badge "GARANTIE #"; the warranty's number, the sale's ticket, the item, its start and end dates,
	 * the date in the info (no cashier, no customer code: the warranty has none); the item's line (no discount, no
	 * promotion: the warranty prints none); the sale's ticket discount ("Remise ticket", only when it has one), the VAT and
	 * the amount paid ("Article sous garantie"); the warranty's barcode; its
	 * message and its end date in the footer. Not a fiscal ticket: the item's line and the amounts may be switched off
	 * (the VAT row then goes too). New: the line's unit price and VAT rate, the item code, off. Locked: the store's name,
	 * DUPLICATA, the warranty's number, ticket, item, start and end dates (what the store checks when the customer comes
	 * back), the item line inside its section, the MF.
	 */
	private static final List<Section> WARRANTY = Collections.unmodifiableList(Arrays.asList(
			section("HEADER", true, Lock.LEGAL, Position.FIRST, false,
					on("LOGO").with(number("width", null, 20, 100), number("maxHeight", null, 5, 60),
							choice("align", "CENTER", "LEFT", "CENTER", "RIGHT")),
					locked("STORE_NAME", Lock.LEGAL).with(size()), off("ADDRESS"), on("TITLE"),
					locked("DUPLICATE", Lock.LEGAL), on("BADGE")),
			section("TOP_TEXT", true, null, null, false,
					on("TEXT").with(text("text", "", 3, 48))),
			section("INFO", true, Lock.FUNCTIONAL, null, false,
					locked("NUMBER", Lock.FUNCTIONAL), locked("TICKET", Lock.FUNCTIONAL), locked("ITEM", Lock.FUNCTIONAL),
					locked("START", Lock.FUNCTIONAL), locked("END", Lock.FUNCTIONAL), on("DATE")),
			section("ITEMS", true, null, null, false,
					locked("LINES", Lock.FUNCTIONAL).with(choice("name", "WRAP", "WRAP", "CUT")), off("ITEM_CODE"),
					off("UNIT_PRICE"), off("VAT")),
			section("TOTALS", true, null, null, false,
					on("TICKET_DISCOUNT"), on("TOTAL_VAT"), on("TOTAL").with(size())),
			section("BARCODE", true, null, null, false),
			section("FOOTER", true, Lock.LEGAL, null, false,
					on("MESSAGE").with(text("text", null, 2, 48)), on("VALIDITY"), on("WEBSITE"), on("EMAIL"), on("PHONE"),
					on("FAX"), locked("MF", Lock.LEGAL), on("FOOTER_NOTE").with(text("text", null, 6, 48)),
					on("SOFTWARE_LABEL"))));

	/**
	 * Step 3 part 2: the tombola slip (printed under the sale ticket when its switch is on, a loyalty member, never on a
	 * duplicate): its own page (the page break it was written with), its title, the ticket's number, its barcode, the
	 * member's name and phone. Its page, size and spacing are the sale ticket's (it prints in the same window): no layout
	 * here. Locked: the ticket's number and the member's name (the draw needs them).
	 */
	private static final List<Section> TOMBOLA_SLIP = Collections.unmodifiableList(Arrays.asList(
			section("SLIP", true, Lock.FUNCTIONAL, Position.FIRST, false,
					on("NEW_PAGE"), on("TITLE"), locked("NUMBER", Lock.FUNCTIONAL), on("BARCODE"),
					locked("NAME", Lock.FUNCTIONAL), on("PHONE"))));

	private TicketBlocks() {
	}

	/** A document's sections in their default order; empty for a document without a template yet. */
	public static List<Section> of(TicketDocumentType type) {
		if (type == TicketDocumentType.SALE) {
			return SALE;
		}
		if (type == TicketDocumentType.RETURN_VOUCHER) {
			return RETURN_VOUCHER;
		}
		if (type == TicketDocumentType.CASH_REFUND) {
			return CASH_REFUND;
		}
		if (type == TicketDocumentType.WARRANTY) {
			return WARRANTY;
		}
		if (type == TicketDocumentType.TOMBOLA_SLIP) {
			return TOMBOLA_SLIP;
		}
		return Collections.emptyList();
	}

	/**
	 * A document's layout options (every customer ticket the same; step 4: an internal ticket its own, INTERNAL_LAYOUT);
	 * none for the tombola slip (the sale ticket's) and for a document without a template yet.
	 */
	public static List<Option> layout(TicketDocumentType type) {
		if (type != null && type.isInternal()) {
			return INTERNAL_LAYOUT;
		}
		return of(type).isEmpty() || type == TicketDocumentType.TOMBOLA_SLIP ? Collections.emptyList() : CUSTOMER_LAYOUT;
	}

	/** A section of a document by its code; null when unknown. */
	public static Section section(TicketDocumentType type, String code) {
		for (Section section : of(type)) {
			if (section.getCode().equals(code)) {
				return section;
			}
		}
		return null;
	}
}
