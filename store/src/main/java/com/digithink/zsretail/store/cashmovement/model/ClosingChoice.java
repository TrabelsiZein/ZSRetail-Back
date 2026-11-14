package com.digithink.zsretail.store.cashmovement.model;

/**
 * 3.2.0, the opening amount: the responsible's answer at the verification of a closed session, "Retrait du tiroir"
 * (docs/modules/cash-sessions.md, "The drawer from one session to the next"). The name is what the database stores.
 */
public enum ClosingChoice {
	/** "Tout retirer": taken = the cash counted, left = 0. */
	ALL,
	/** "Rien retirer": taken = 0, left = the cash counted. */
	NONE,
	/** "Retirer un montant": above 0, not above the cash counted. */
	AMOUNT
}
