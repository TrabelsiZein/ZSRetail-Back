package com.digithink.zsretail.core.tickettemplate.link;

import com.digithink.zsretail.core.tickettemplate.TicketDocumentType;

/**
 * 3.5.0, ticket templates step 5: the head office's side: a template saved or reset there is a change of the copies
 * down (domain TICKET_TEMPLATES, its code the document type), recorded in the save's transaction for every store; the
 * stores whose switch is on apply it. One bean on a head office (headoffice.service.TicketTemplateDownProvider); none
 * on a store.
 */
public interface TicketTemplateChanges {

	/** A document's template saved or reset (its row deleted: the stores print the built-in default). */
	void changed(TicketDocumentType type);
}
