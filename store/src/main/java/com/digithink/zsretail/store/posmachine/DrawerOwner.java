package com.digithink.zsretail.store.posmachine;

/**
 * 3.2.0, POS machines step 2, part B: who the cash drawer belongs to, the General setup choice CASH_DRAWER_OWNER of a
 * store ("Le tiroir-caisse appartient"). docs/modules/pos-machines.md, "The drawer belongs to".
 */
public enum DrawerOwner {

	/** To the cashier (the default, the behaviour of before): nothing refused, the session follows the cashier. */
	CASHIER,
	/**
	 * To the POS machine: a session opens only on a registered, active machine, one open session per machine, selling,
	 * putting on hold and returns only from the session's machine; closing from any PC.
	 */
	POS_MACHINE;

	/** The setting's value read: POS_MACHINE in any case, anything else (none, blank, unknown) CASHIER. */
	public static DrawerOwner of(String value) {
		return value != null && POS_MACHINE.name().equalsIgnoreCase(value.trim()) ? POS_MACHINE : CASHIER;
	}
}
