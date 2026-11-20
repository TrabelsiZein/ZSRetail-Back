package com.digithink.zsretail.core.communication.service;

import java.util.List;

/**
 * 3.3.0, communication step 4: the tickets whose number contains a text, for the search of the Communication page (a
 * message or an event found by its ticket number). Answered by the store (its sales_header); absent elsewhere, where the
 * search is on the card and the address only.
 */
public interface CommunicationTicketSearch {

	/** The ids of the tickets whose number contains {@code text}, the newest {@code max} at most. */
	List<Long> ticketIds(String text, int max);
}
