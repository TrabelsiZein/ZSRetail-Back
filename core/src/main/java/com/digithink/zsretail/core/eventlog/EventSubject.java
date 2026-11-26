package com.digithink.zsretail.core.eventlog;

/**
 * 3.4.0, event log: what an event happened to (event_log.subject_type, written as its name). The history of a subject is
 * its events in order: subject type + subject id. The table stores the name as text, so a row stays readable when a
 * subject type is added or renamed later.
 */
public enum EventSubject {
	/** A cart of the till before it is validated, identified by the UUID the till makes at its first line. */
	CART,
	/** A sales ticket (sales_header), validated or pending. */
	TICKET,
	RETURN,
	SESSION,
	/** The store's day (the store tickets X and Z). */
	STORE_DAY,
	CASH_MOVEMENT,
	/** A till right asked at the till (badge or role), identified by the action code. */
	TILL_RIGHT,
	LOGIN,
	USER,
	ROLE,
	SETTING,
	ITEM,
	PRICE,
	PROMOTION,
	INVENTORY,
	PURCHASE_ORDER,
	/** One import run (Data import page). */
	IMPORT,
	/** One run of a synchronisation with the ERP or the head office. */
	SYNC,
	/** Step 9: a payment method (its settings: money taken at the till). */
	PAYMENT_METHOD,
	/** Step 9: a loyalty member's points or a loyalty program. */
	LOYALTY,
	/** 3.5.0, ticket templates step 2: a document's template, identified by the document type (SALE). */
	TICKET_TEMPLATE,
	/** The event log itself: its start, its settings, its cleanups. */
	EVENT_LOG
}
