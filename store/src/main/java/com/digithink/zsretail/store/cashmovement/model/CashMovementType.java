package com.digithink.zsretail.store.cashmovement.model;

/**
 * 3.2.0, POS machines step 5: the three fixed kinds of cash movement (docs/modules/cash-sessions.md, "Cash movements").
 * The name is what the database stores (cash_movement.type).
 */
public enum CashMovementType {
	/** Out: cash taken to the safe, the bank or the owner. */
	PRELEVEMENT(false, false),
	/** Out: a small expense paid from the drawer; its note says what for. */
	DEPENSE(false, true),
	/** In: change added to the drawer. */
	APPORT(true, false);

	private final boolean in;
	private final boolean noteRequired;

	CashMovementType(boolean in, boolean noteRequired) {
		this.in = in;
		this.noteRequired = noteRequired;
	}

	/** True when the cash goes into the drawer (APPORT); false when it goes out. */
	public boolean isIn() {
		return in;
	}

	/** True for DEPENSE: the note is required. */
	public boolean isNoteRequired() {
		return noteRequired;
	}
}
