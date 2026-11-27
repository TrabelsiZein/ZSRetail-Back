package com.digithink.zsretail.core.tickettemplate.link;

/**
 * 3.5.0, ticket templates step 5: the store's side of the head office's management of its templates, seen from core
 * (the service and the API): whether the head office manages them now (the switch "Le siège gère les modèles de
 * tickets" of the store's row, received with the heartbeat and saved). One bean on a store linked to a head office
 * (store.holink.service.TicketTemplatesManaged); none elsewhere: never managed.
 */
public interface TicketTemplateLink {

	/** True while the head office manages this store's templates: they are read-only here. */
	boolean managedByHeadOffice();
}
