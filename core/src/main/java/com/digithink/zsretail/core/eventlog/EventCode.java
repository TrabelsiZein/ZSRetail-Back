package com.digithink.zsretail.core.eventlog;

import static com.digithink.zsretail.core.eventlog.EventImportance.ALERT;
import static com.digithink.zsretail.core.eventlog.EventImportance.NOTABLE;
import static com.digithink.zsretail.core.eventlog.EventImportance.STEP;

/**
 * 3.4.0, the catalogue of the event log: every event the application writes, with its subject type and its importance.
 * The importance is decided here, never by the caller nor by the till. A new event is one entry here plus one line where
 * the gesture happens ({@code EventLog.write}). The table stores the code as text (event_log.code): an entry removed
 * later leaves its rows readable. Who writes each event: docs/modules/event-log.md.
 */
public enum EventCode {

	// ── The till: the cart before validation (sent by the till) ──
	CART_LINE_ADDED(EventSubject.CART, STEP),
	CART_QTY_RAISED(EventSubject.CART, STEP),
	CART_QTY_LOWERED(EventSubject.CART, NOTABLE),
	CART_LINE_DELETED(EventSubject.CART, NOTABLE),
	CART_CLEARED(EventSubject.CART, NOTABLE),
	LINE_DISCOUNT(EventSubject.CART, NOTABLE),
	CART_DISCOUNT(EventSubject.CART, NOTABLE),

	// ── Tickets ──
	PENDING_SAVED(EventSubject.TICKET, STEP),
	/** Sent by the till: taking a pending ticket back changes nothing on the server. */
	PENDING_TAKEN_BACK(EventSubject.TICKET, STEP),
	PENDING_DELETED(EventSubject.TICKET, NOTABLE),
	SALE_VALIDATED(EventSubject.TICKET, STEP),
	/** Sent by the page that prints the duplicate: the ticket printer opens the drawer on every print. */
	RECEIPT_REPRINTED(EventSubject.TICKET, ALERT),
	PAYMENT_CHANGED(EventSubject.TICKET, ALERT),

	// ── Returns ──
	RETURN_DONE(EventSubject.RETURN, NOTABLE),
	/** Sent by the page that prints the voucher's duplicate (the drawer opens, as for a ticket). */
	RETURN_VOUCHER_REPRINTED(EventSubject.RETURN, ALERT),

	// ── Cash ──
	CASH_MOVEMENT(EventSubject.CASH_MOVEMENT, NOTABLE),

	// ── Sessions and the store's day ──
	SESSION_OPENED(EventSubject.SESSION, STEP),
	SESSION_OPENING_GAP(EventSubject.SESSION, NOTABLE),
	/** Step 3: opened while the previous session of its drawer is closed but not verified. */
	SESSION_OPENED_PREVIOUS_UNVERIFIED(EventSubject.SESSION, NOTABLE),
	SESSION_CLOSED(EventSubject.SESSION, STEP),
	SESSION_CLOSED_WITH_DIFFERENCE(EventSubject.SESSION, ALERT),
	SESSION_VERIFIED(EventSubject.SESSION, NOTABLE),
	/** Sent by the till or the page that prints a session's ticket. */
	SESSION_TICKET_PRINTED(EventSubject.SESSION, STEP),
	/** Sent by the page "Journée du magasin". */
	TICKET_X_PRINTED(EventSubject.STORE_DAY, STEP),
	TICKET_Z_PRINTED(EventSubject.STORE_DAY, NOTABLE),

	// ── Till rights (badge or role) ──
	TILL_RIGHT_USED(EventSubject.TILL_RIGHT, STEP),
	TILL_RIGHT_REFUSED(EventSubject.TILL_RIGHT, ALERT),

	// ── Logins ──
	LOGIN_OK(EventSubject.LOGIN, STEP),
	/** Step 4 (Zein): Notable, a typo at the morning login is not an Alert. */
	LOGIN_REFUSED(EventSubject.LOGIN, NOTABLE),
	/** Step 4: the 5th refused login in a row on one login or from one address (and each one after it). */
	LOGIN_REFUSED_REPEATED(EventSubject.LOGIN, ALERT),
	/** Step 3: POST /logout, which only writes this event (the token stays valid until it expires). */
	LOGOUT(EventSubject.LOGIN, STEP),

	// ── Users, roles, badges ──
	USER_CREATED(EventSubject.USER, NOTABLE),
	USER_UPDATED(EventSubject.USER, NOTABLE),
	USER_ROLE_CHANGED(EventSubject.USER, ALERT),
	PASSWORD_CHANGED(EventSubject.USER, ALERT),
	USER_ENABLED(EventSubject.USER, NOTABLE),
	USER_DISABLED(EventSubject.USER, NOTABLE),
	USER_DELETED(EventSubject.USER, ALERT),
	BADGE_GENERATED(EventSubject.USER, NOTABLE),
	BADGE_REVOKED(EventSubject.USER, NOTABLE),
	BADGE_REACTIVATED(EventSubject.USER, NOTABLE),
	ROLE_CREATED(EventSubject.ROLE, NOTABLE),
	ROLE_RIGHTS_CHANGED(EventSubject.ROLE, ALERT),
	ROLE_DELETED(EventSubject.ROLE, ALERT),

	// ── Settings, prices, promotions ──
	SETTING_CHANGED(EventSubject.SETTING, NOTABLE),
	PRICE_SAVED(EventSubject.PRICE, NOTABLE),
	PRICE_DELETED(EventSubject.PRICE, NOTABLE),
	OWN_PRICE_SET(EventSubject.ITEM, NOTABLE),
	OWN_PRICE_GIVEN_BACK(EventSubject.ITEM, NOTABLE),
	PROMOTION_SAVED(EventSubject.PROMOTION, NOTABLE),
	PROMOTION_DELETED(EventSubject.PROMOTION, NOTABLE),

	// ── Stock, inventory, purchases ──
	STOCK_ADJUSTED(EventSubject.ITEM, ALERT),
	COST_ADJUSTED(EventSubject.ITEM, NOTABLE),
	INVENTORY_CREATED(EventSubject.INVENTORY, NOTABLE),
	INVENTORY_VALIDATED(EventSubject.INVENTORY, ALERT),
	INVENTORY_DELETED(EventSubject.INVENTORY, ALERT),
	PURCHASE_PROCESSED(EventSubject.PURCHASE_ORDER, NOTABLE),
	RECEPTION_DONE(EventSubject.PURCHASE_ORDER, NOTABLE),

	// ── Runs: one event per run ──
	IMPORT_RUN(EventSubject.IMPORT, NOTABLE),
	ERP_SYNC_RUN(EventSubject.SYNC, STEP),
	HEAD_OFFICE_SYNC_RUN(EventSubject.SYNC, STEP),

	// ── The event log itself ──
	/**
	 * Written at each start with the module on: a hole in time between two of them shows it was off. Notable, not an
	 * Alert (step 2): a store restarts every morning.
	 */
	EVENTLOG_STARTED(EventSubject.EVENT_LOG, NOTABLE),
	EVENTLOG_SETTINGS_CHANGED(EventSubject.EVENT_LOG, ALERT),
	EVENTLOG_CLEANUP(EventSubject.EVENT_LOG, ALERT),
	/** Step 4, sent by the till: its queue was full, events were dropped (how many, from when to when). */
	EVENTLOG_EVENTS_DROPPED(EventSubject.EVENT_LOG, ALERT);

	/** The longest code the table holds (event_log.code). */
	public static final int CODE_LENGTH = 60;

	/**
	 * Step 4: the only codes the till may send (POST /event-log/batch): the cart, the discount gestures, a pending ticket
	 * taken back, the reprints, the tickets X / Z and the session tickets, and its own queue's losses. Anything else is
	 * refused, a money event above all: the server writes those itself.
	 */
	public static final java.util.Set<EventCode> FROM_TILL = java.util.Collections.unmodifiableSet(java.util.EnumSet.of(
			CART_LINE_ADDED, CART_QTY_RAISED, CART_QTY_LOWERED, CART_LINE_DELETED, CART_CLEARED, LINE_DISCOUNT,
			CART_DISCOUNT, PENDING_TAKEN_BACK, RECEIPT_REPRINTED, RETURN_VOUCHER_REPRINTED, TICKET_X_PRINTED,
			TICKET_Z_PRINTED, SESSION_TICKET_PRINTED, EVENTLOG_EVENTS_DROPPED));

	private final EventSubject subject;
	private final int importance;

	EventCode(EventSubject subject, int importance) {
		this.subject = subject;
		this.importance = importance;
	}

	public EventSubject getSubject() {
		return subject;
	}

	public int getImportance() {
		return importance;
	}
}
