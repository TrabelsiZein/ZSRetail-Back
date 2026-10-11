package com.digithink.zsretail.store.service;

import java.util.List;

import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.ReturnLine;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;

import lombok.Value;

/**
 * Something that follows the tagged pieces of the tickets (RFID: store.rfid.service.RfidSaleTags); none by default.
 * Called by the sale, pending and return code (SalesHeaderService, ReturnHeaderService) inside their transaction, so a
 * failure here fails the ticket. The tags are those the POS sends with a line (SaleLineDTO.epcs, read at the POS) and
 * with a return (ProcessReturnRequestDTO.returnedEpcs); the sale code never reads them. docs/modules/rfid.md, "POS".
 */
public interface SaleTagHooks {

	/** A ticket put on hold, new or saved again: the tags of its lines now, instead of those it held before. */
	void ticketHeld(SalesHeader ticket, List<LineTags> lines);

	/** A ticket paid (direct or a pending ticket completed): the tags of its paid lines. */
	void ticketCompleted(SalesHeader ticket, List<LineTags> lines);

	/** A pending ticket cancelled: it holds no tag any more. */
	void ticketCancelled(SalesHeader ticket);

	/** A return saved: its lines, and the tags the POS read for the lines returned in part (none: empty). */
	void returned(ReturnHeader ret, List<ReturnedLine> lines, List<String> epcsRead);

	/** A line of a ticket and the tags sent with it; the paid line only (a free line holds no tag). */
	@Value
	class LineTags {
		SalesLine line;
		List<String> epcs;
	}

	/** A return line; wholeRemaining: it returns all that was left of its sales line. */
	@Value
	class ReturnedLine {
		ReturnLine line;
		boolean wholeRemaining;
	}
}
