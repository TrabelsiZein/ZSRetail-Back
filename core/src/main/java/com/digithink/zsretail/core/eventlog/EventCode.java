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
	LOGIN_REFUSED(EventSubject.LOGIN, ALERT),
	/** Sent by the browser: the API has no logout. */
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
	/** Written at each start with the module on: a hole in time between two of them shows it was off. */
	EVENTLOG_STARTED(EventSubject.EVENT_LOG, ALERT),
	EVENTLOG_SETTINGS_CHANGED(EventSubject.EVENT_LOG, ALERT),
	EVENTLOG_CLEANUP(EventSubject.EVENT_LOG, ALERT);

	/** The longest code the table holds (event_log.code). */
	public static final int CODE_LENGTH = 60;

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
