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
	CART_LINE_ADDED(EventSubject.CART, STEP, "Ligne ajoutée"),
	CART_QTY_RAISED(EventSubject.CART, STEP, "Quantité augmentée"),
	CART_QTY_LOWERED(EventSubject.CART, NOTABLE, "Quantité diminuée"),
	CART_LINE_DELETED(EventSubject.CART, NOTABLE, "Ligne supprimée"),
	CART_CLEARED(EventSubject.CART, NOTABLE, "Panier vidé"),
	LINE_DISCOUNT(EventSubject.CART, NOTABLE, "Remise sur la ligne"),
	CART_DISCOUNT(EventSubject.CART, NOTABLE, "Remise sur le total"),
	/** Step 5, sent by the till: a logout with lines in the cart (the cart is not emptied: a gap kept for later). */
	CART_LEFT_AT_LOGOUT(EventSubject.CART, NOTABLE, "Panier laissé à la déconnexion"),
	/** Step 5, sent by the till when it opens again: a cart with lines lost with a page reload (scan + refresh). */
	CART_LOST_ON_RELOAD(EventSubject.CART, NOTABLE, "Panier perdu au rechargement"),
	/** Step 5b, sent by the till: a promo code added to the cart (the cart total before -> after). */
	PROMO_CODE_ADDED(EventSubject.CART, NOTABLE, "Code promo ajouté"),
	/** Step 5b, sent by the till: a promo code taken off the cart. */
	PROMO_CODE_REMOVED(EventSubject.CART, NOTABLE, "Code promo retiré"),
	/** Step 5b, sent by the till: the cart's customer chosen or changed (old -> new, the total before -> after). */
	CART_CUSTOMER_CHANGED(EventSubject.CART, NOTABLE, "Client du panier changé"),

	// ── Tickets ──
	PENDING_SAVED(EventSubject.TICKET, STEP, "Ticket mis en attente"),
	/** Sent by the till: taking a pending ticket back changes nothing on the server. */
	PENDING_TAKEN_BACK(EventSubject.TICKET, STEP, "Ticket en attente repris"),
	PENDING_DELETED(EventSubject.TICKET, NOTABLE, "Ticket en attente supprimé"),
	SALE_VALIDATED(EventSubject.TICKET, STEP, "Vente validée"),
	/** Sent by the page that prints the duplicate: the ticket printer opens the drawer on every print. */
	RECEIPT_REPRINTED(EventSubject.TICKET, ALERT, "Ticket réimprimé"),
	PAYMENT_CHANGED(EventSubject.TICKET, ALERT, "Paiement modifié"),

	// ── Returns ──
	RETURN_DONE(EventSubject.RETURN, NOTABLE, "Retour effectué"),
	/** Sent by the page that prints the voucher's duplicate (the drawer opens, as for a ticket). */
	RETURN_VOUCHER_REPRINTED(EventSubject.RETURN, ALERT, "Bon de retour réimprimé"),

	// ── Cash ──
	CASH_MOVEMENT(EventSubject.CASH_MOVEMENT, NOTABLE, "Mouvement de caisse"),

	// ── Sessions and the store's day ──
	SESSION_OPENED(EventSubject.SESSION, STEP, "Session ouverte"),
	SESSION_OPENING_GAP(EventSubject.SESSION, NOTABLE, "Écart à l'ouverture"),
	/** Step 3: opened while the previous session of its drawer is closed but not verified. */
	SESSION_OPENED_PREVIOUS_UNVERIFIED(EventSubject.SESSION, NOTABLE, "Ouverte sans clôture de la précédente"),
	SESSION_CLOSED(EventSubject.SESSION, STEP, "Session fermée"),
	SESSION_CLOSED_WITH_DIFFERENCE(EventSubject.SESSION, ALERT, "Session fermée avec écart"),
	SESSION_VERIFIED(EventSubject.SESSION, NOTABLE, "Session clôturée"),
	/** Sent by the till or the page that prints a session's ticket. */
	SESSION_TICKET_PRINTED(EventSubject.SESSION, STEP, "Ticket de session imprimé"),
	/** Sent by the page "Journée du magasin". */
	TICKET_X_PRINTED(EventSubject.STORE_DAY, STEP, "Ticket X imprimé"),
	TICKET_Z_PRINTED(EventSubject.STORE_DAY, NOTABLE, "Ticket Z imprimé"),

	// ── Till rights (badge or role) ──
	TILL_RIGHT_USED(EventSubject.TILL_RIGHT, STEP, "Droit de caisse utilisé"),
	TILL_RIGHT_REFUSED(EventSubject.TILL_RIGHT, ALERT, "Droit de caisse refusé"),

	// ── Logins ──
	LOGIN_OK(EventSubject.LOGIN, STEP, "Connexion"),
	/**
	 * Fixes after the test round: the till's "Retour à l'administration" popup, its password right (the login request
	 * marked login-purpose TILL_ADMIN_RETURN): not a login. Its wrong password stays a LOGIN_REFUSED (said in the text).
	 */
	ADMIN_RETURN_VERIFIED(EventSubject.LOGIN, STEP, "Retour à l'administration"),
	/** Step 4 (Zein): Notable, a typo at the morning login is not an Alert. */
	LOGIN_REFUSED(EventSubject.LOGIN, NOTABLE, "Connexion refusée"),
	/** Step 4: the 5th refused login in a row on one login or from one address (and each one after it). */
	LOGIN_REFUSED_REPEATED(EventSubject.LOGIN, ALERT, "Connexions refusées à répétition"),
	/** Step 3: POST /logout, which only writes this event (the token stays valid until it expires). */
	LOGOUT(EventSubject.LOGIN, STEP, "Déconnexion"),

	// ── Users, roles, badges ──
	USER_CREATED(EventSubject.USER, NOTABLE, "Utilisateur créé"),
	USER_UPDATED(EventSubject.USER, NOTABLE, "Utilisateur modifié"),
	USER_ROLE_CHANGED(EventSubject.USER, ALERT, "Rôle de l'utilisateur changé"),
	PASSWORD_CHANGED(EventSubject.USER, ALERT, "Mot de passe changé"),
	USER_ENABLED(EventSubject.USER, NOTABLE, "Utilisateur activé"),
	USER_DISABLED(EventSubject.USER, NOTABLE, "Utilisateur désactivé"),
	USER_DELETED(EventSubject.USER, ALERT, "Utilisateur supprimé"),
	BADGE_GENERATED(EventSubject.USER, NOTABLE, "Badge généré"),
	BADGE_REVOKED(EventSubject.USER, NOTABLE, "Badge révoqué"),
	BADGE_REACTIVATED(EventSubject.USER, NOTABLE, "Badge réactivé"),
	ROLE_CREATED(EventSubject.ROLE, NOTABLE, "Rôle créé"),
	ROLE_RIGHTS_CHANGED(EventSubject.ROLE, ALERT, "Droits d'un rôle changés"),
	ROLE_DELETED(EventSubject.ROLE, ALERT, "Rôle supprimé"),

	// ── Settings, prices, promotions ──
	SETTING_CHANGED(EventSubject.SETTING, NOTABLE, "Paramètre changé"),
	PRICE_SAVED(EventSubject.PRICE, NOTABLE, "Prix enregistré"),
	PRICE_DELETED(EventSubject.PRICE, NOTABLE, "Prix supprimé"),
	OWN_PRICE_SET(EventSubject.ITEM, NOTABLE, "Prix propre fixé"),
	OWN_PRICE_GIVEN_BACK(EventSubject.ITEM, NOTABLE, "Prix propre rendu"),
	PROMOTION_SAVED(EventSubject.PROMOTION, NOTABLE, "Promotion enregistrée"),
	PROMOTION_DELETED(EventSubject.PROMOTION, NOTABLE, "Promotion supprimée"),

	// ── Stock, inventory, purchases ──
	STOCK_ADJUSTED(EventSubject.ITEM, ALERT, "Stock ajusté"),
	COST_ADJUSTED(EventSubject.ITEM, NOTABLE, "CMP ajusté"),
	INVENTORY_CREATED(EventSubject.INVENTORY, NOTABLE, "Inventaire créé"),
	INVENTORY_VALIDATED(EventSubject.INVENTORY, ALERT, "Inventaire validé"),
	INVENTORY_DELETED(EventSubject.INVENTORY, ALERT, "Inventaire supprimé"),
	PURCHASE_PROCESSED(EventSubject.PURCHASE_ORDER, NOTABLE, "Achat enregistré"),
	RECEPTION_DONE(EventSubject.PURCHASE_ORDER, NOTABLE, "Réception effectuée"),

	// ── Runs: one event per run ──
	IMPORT_RUN(EventSubject.IMPORT, NOTABLE, "Import de données"),
	ERP_SYNC_RUN(EventSubject.SYNC, STEP, "Synchronisation ERP"),
	HEAD_OFFICE_SYNC_RUN(EventSubject.SYNC, STEP, "Synchronisation siège"),

	// ── The event log itself ──
	/**
	 * Written at each start with the module on: a hole in time between two of them shows it was off. Notable, not an
	 * Alert (step 2): a store restarts every morning.
	 */
	EVENTLOG_STARTED(EventSubject.EVENT_LOG, NOTABLE, "Journal démarré"),
	EVENTLOG_SETTINGS_CHANGED(EventSubject.EVENT_LOG, ALERT, "Réglages du journal changés"),
	EVENTLOG_CLEANUP(EventSubject.EVENT_LOG, ALERT, "Nettoyage du journal"),
	/** Step 4, sent by the till: its queue was full, events were dropped (how many, from when to when). */
	EVENTLOG_EVENTS_DROPPED(EventSubject.EVENT_LOG, ALERT, "Événements perdus par la caisse");

	/** The longest code the table holds (event_log.code). */
	public static final int CODE_LENGTH = 60;

	/**
	 * Step 4: the only codes the till may send (POST /event-log/batch): the cart, the discount gestures, a pending ticket
	 * taken back, the reprints, the tickets X / Z and the session tickets, and its own queue's losses. Anything else is
	 * refused, a money event above all: the server writes those itself.
	 */
	public static final java.util.Set<EventCode> FROM_TILL = java.util.Collections.unmodifiableSet(java.util.EnumSet.of(
			CART_LINE_ADDED, CART_QTY_RAISED, CART_QTY_LOWERED, CART_LINE_DELETED, CART_CLEARED, LINE_DISCOUNT,
			CART_DISCOUNT, CART_LEFT_AT_LOGOUT, CART_LOST_ON_RELOAD, PROMO_CODE_ADDED, PROMO_CODE_REMOVED,
			CART_CUSTOMER_CHANGED, PENDING_TAKEN_BACK, RECEIPT_REPRINTED,
			RETURN_VOUCHER_REPRINTED, TICKET_X_PRINTED,
			TICKET_Z_PRINTED, SESSION_TICKET_PRINTED, EVENTLOG_EVENTS_DROPPED));

	private final EventSubject subject;
	private final int importance;
	private final String label;

	/** {@code label}: step 6, the French name of the action on the Journal page and in its Excel file. */
	EventCode(EventSubject subject, int importance, String label) {
		this.subject = subject;
		this.importance = importance;
		this.label = label;
	}

	public EventSubject getSubject() {
		return subject;
	}

	public int getImportance() {
		return importance;
	}

	public String getLabel() {
		return label;
	}

	/** The French label of a code as stored (a code no longer in the catalogue: the code itself). */
	public static String labelOf(String code) {
		if (code == null) {
			return null;
		}
		try {
			return valueOf(code).label;
		} catch (IllegalArgumentException gone) {
			return code;
		}
	}
}
