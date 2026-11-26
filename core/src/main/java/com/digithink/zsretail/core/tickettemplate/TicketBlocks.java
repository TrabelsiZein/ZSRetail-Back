package com.digithink.zsretail.core.tickettemplate;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 3.5.0, ticket templates step 2: the block catalogue, the back end's (docs/modules/ticket-templates.md, "The blocks").
 * A document's template is two levels: its <b>sections</b>, in print order (moved up and down on the page), and inside
 * each section its <b>elements</b>, switched on or off (their order is the section's). The header always prints first
 * and the tombola slip last ({@link Position}).
 * <ul>
 * <li>A locked section or element ({@link Lock}) cannot be switched off: the save is refused.</li>
 * <li>The VAT row of each item ({@code ITEMS.VAT}) may be off only while the section {@code VAT_BY_RATE} is on (the
 * rates stay on the ticket): {@link Element#getOffNeeds}.</li>
 * <li>A linked element ({@link Element#getLinked}) takes, while the document has no template row, the value of the old
 * setting it replaces (Company Information, General setup); the old code keeps reading those settings.</li>
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
		FUNCTIONAL,
		/** Decided by its own setting, not here (the tombola slip: TOMBOLA_ENABLED). */
		SETTING
	}

	/** A section that does not move. */
	public enum Position {
		FIRST, LAST
	}

	/** An element of a section: a switch. */
	@Getter
	@AllArgsConstructor
	public static final class Element {
		private final String code;
		private final boolean defaultOn;
		/** Null: it can be switched off. */
		private final Lock lock;
		/** The old setting it takes its default from while no template exists (null: none). */
		private final String linked;
		/** The section that must be on for this element to be off (null: none). */
		private final String offNeeds;
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

	/** Company Information, "Print the address on the ticket" (null = no). */
	public static final String LINK_PRINT_ADDRESS = "companyInformation.receiptPrintAddress";
	/** Company Information, the thank-you line (null = yes). */
	public static final String LINK_THANK_YOU = "companyInformation.receiptShowThankYou";
	/** Company Information, the "ZS Retail" label (null = yes). */
	public static final String LINK_SOFTWARE_LABEL = "companyInformation.receiptShowSoftwareLabel";
	/** General setup TICKET_SHOW_LOYALTY_EARNED (absent = yes). */
	public static final String LINK_LOYALTY_EARNED = "generalSetup.TICKET_SHOW_LOYALTY_EARNED";
	/** General setup TICKET_SHOW_LOYALTY_BALANCE (absent = yes). */
	public static final String LINK_LOYALTY_BALANCE = "generalSetup.TICKET_SHOW_LOYALTY_BALANCE";

	public static final String VAT_BY_RATE = "VAT_BY_RATE";

	private static Element on(String code) {
		return new Element(code, true, null, null, null);
	}

	private static Element off(String code) {
		return new Element(code, false, null, null, null);
	}

	private static Element locked(String code, Lock lock) {
		return new Element(code, true, lock, null, null);
	}

	private static Element linked(String code, String setting) {
		return new Element(code, true, null, setting, null);
	}

	private static Section section(String code, boolean defaultOn, Lock lock, Position position, boolean warnOff,
			Element... elements) {
		return new Section(code, defaultOn, lock, position, warnOff, Collections.unmodifiableList(Arrays.asList(elements)));
	}

	/**
	 * The sale ticket (its split bill, its duplicate, the tombola slip), in the order it prints today. Not locked, to check
	 * with the accountant: the address, the unit price.
	 */
	private static final List<Section> SALE = Collections.unmodifiableList(Arrays.asList(
			section("HEADER", true, Lock.LEGAL, Position.FIRST, false,
					on("LOGO"), locked("STORE_NAME", Lock.LEGAL), linked("ADDRESS", LINK_PRINT_ADDRESS), on("TITLE"),
					locked("DUPLICATE", Lock.LEGAL)),
			section("INFO", true, Lock.LEGAL, null, false,
					locked("NUMBER", Lock.LEGAL), locked("DATE", Lock.LEGAL), on("CASHIER"), on("CUSTOMER_CODE")),
			section("ITEMS", true, Lock.LEGAL, null, false,
					locked("LINES", Lock.LEGAL), off("UNIT_PRICE"), on("DISCOUNT"),
					new Element("VAT", true, null, null, VAT_BY_RATE)),
			section("TOTALS", true, Lock.LEGAL, null, false,
					on("TICKET_DISCOUNT"), locked("TOTAL_VAT", Lock.LEGAL), locked("TOTAL", Lock.LEGAL)),
			section(VAT_BY_RATE, false, null, null, false),
			section("PAYMENTS", true, Lock.FUNCTIONAL, null, false),
			section("LOYALTY", true, null, null, false,
					on("CARD"), linked("EARNED", LINK_LOYALTY_EARNED), on("USED"), linked("BALANCE", LINK_LOYALTY_BALANCE)),
			section("BARCODE", true, null, null, true),
			section("FOOTER", true, Lock.LEGAL, null, false,
					linked("THANK_YOU", LINK_THANK_YOU), on("CONTACT"), locked("MF", Lock.LEGAL), on("FOOTER_NOTE"),
					linked("SOFTWARE_LABEL", LINK_SOFTWARE_LABEL)),
			section("TOMBOLA", true, Lock.SETTING, Position.LAST, false)));

	private TicketBlocks() {
	}

	/** A document's sections in their default order; empty for a document without a template yet. */
	public static List<Section> of(TicketDocumentType type) {
		return type == TicketDocumentType.SALE ? SALE : Collections.emptyList();
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
