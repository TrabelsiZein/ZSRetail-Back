package com.digithink.zsretail.core.tickettemplate;

/**
 * 3.5.0, ticket templates (docs/modules/ticket-templates.md): the documents a template is made for, one template row
 * each (ticket_template.document_type, written as its name). Step 2 makes the sale ticket's only ({@link #available});
 * the others are listed so that the page shows them as coming: steps 3 (the other customer tickets) and 4 (the internal
 * tickets). Step 3: the return voucher and the cash refund slip.
 */
public enum TicketDocumentType {
	/** The sale ticket, its split bill, its duplicate and the tombola slip under it. */
	SALE(true),
	/** Step 3: the return voucher, its first print and its reprint. */
	RETURN_VOUCHER(true),
	/** Step 3: the cash refund slip. */
	CASH_REFUND(true),
	/** Step 3 part 2: the warranty ticket and its duplicate. */
	WARRANTY(true),
	/** Step 3 part 2: the tombola slip, printed under the sale ticket (its look; whether it prints: the sale ticket's switch). */
	TOMBOLA_SLIP(true),
	/** Step 4, an internal ticket: the session ticket X and Z (one template for both). */
	SESSION_REPORT(true, true),
	/** Step 4: the store ticket X and Z, of the store or of one POS machine (one template for both). */
	STORE_REPORT(true, true),
	/** Step 4: the cashier's closing ticket when CASHIER_CLOSING_TICKET is DECLARED (ItemSelection.vue). */
	CLOSING_DECLARED(true, true),
	/** Step 4: the slip of a cash movement. */
	CASH_MOVEMENT_SLIP(true, true),
	/** Step 4: the slip of a closing withdrawal. */
	CLOSING_WITHDRAWAL_SLIP(true, true);

	private final boolean available;
	private final boolean internal;

	TicketDocumentType(boolean available) {
		this(available, false);
	}

	TicketDocumentType(boolean available, boolean internal) {
		this.available = available;
		this.internal = internal;
	}

	/** A template can be saved for it (its builder reads one). */
	public boolean isAvailable() {
		return available;
	}

	/**
	 * Step 4: an internal ticket (families B and C): a layout only (width, size, spacing, the rows at zero hidden), no
	 * blocks (TicketBlocks.of is empty; a save that sends blocks is refused).
	 */
	public boolean isInternal() {
		return internal;
	}

	/** The type of a name, any case; null when unknown. */
	public static TicketDocumentType of(String name) {
		if (name == null) {
			return null;
		}
		for (TicketDocumentType type : values()) {
			if (type.name().equalsIgnoreCase(name.trim())) {
				return type;
			}
		}
		return null;
	}
}
