package com.digithink.zsretail.core.tickettemplate;

/**
 * 3.5.0, ticket templates (docs/modules/ticket-templates.md): the documents a template is made for, one template row
 * each (ticket_template.document_type, written as its name). Step 2 makes the sale ticket's only ({@link #available});
 * the others are listed so that the page shows them as coming: steps 3 (the other customer tickets) and 4 (the internal
 * tickets).
 */
public enum TicketDocumentType {
	/** The sale ticket, its split bill, its duplicate and the tombola slip under it. */
	SALE(true),
	RETURN_VOUCHER(false),
	CASH_REFUND(false),
	WARRANTY(false),
	SESSION_REPORT(false),
	STORE_REPORT(false),
	CLOSING_DECLARED(false),
	CASH_MOVEMENT_SLIP(false),
	CLOSING_WITHDRAWAL_SLIP(false);

	private final boolean available;

	TicketDocumentType(boolean available) {
		this.available = available;
	}

	/** A template can be saved for it (its builder reads one). */
	public boolean isAvailable() {
		return available;
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
