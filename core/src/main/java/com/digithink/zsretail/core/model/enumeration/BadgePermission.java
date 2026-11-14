package com.digithink.zsretail.core.model.enumeration;

/**
 * The six till actions that need a right (customer list, return, line and total discount, closing a session with a
 * cash difference; 3.2.0, POS machines step 5: a cash movement). The constant name is what the database stores
 * (badge_scan_log.functionality, till_grant.action) and what the till sends to POST /badge/scan; {@link #key()} is the
 * role permission key (app_role_permission) that holds the right since step C, served to the frontend by GET /config
 * (tillActions).
 */
public enum BadgePermission {
	CONSULT_CUSTOMER_LIST("till:consult-customer-list", false),
	MAKE_RETURN("till:make-return", true),
	APPLY_LINE_DISCOUNT("till:apply-line-discount", true),
	APPLY_TOTAL_DISCOUNT("till:apply-total-discount", true),
	CLOSE_SESSION_WITH_DISCREPANCY("till:close-session-with-discrepancy", true),
	/** 3.2.0, POS machines step 5: cash taken out of or put into the drawer (PRELEVEMENT, DEPENSE, APPORT). */
	CASH_MOVEMENT("till:cash-movement", true);

	/**
	 * The five actions of step C (3.1.0): given to every role of a new store and moved once from the users' badge cards
	 * (TillRightsMigration). CASH_MOVEMENT (3.2.0) is not one: ADMIN and RESPONSIBLE hold it, a cashier does not.
	 */
	public static final java.util.Set<BadgePermission> STEP_C = java.util.Collections.unmodifiableSet(
			java.util.EnumSet.of(CONSULT_CUSTOMER_LIST, MAKE_RETURN, APPLY_LINE_DISCOUNT, APPLY_TOTAL_DISCOUNT,
					CLOSE_SESSION_WITH_DISCREPANCY));

	private final String key;
	private final boolean checkedOnRequest;

	BadgePermission(String key, boolean checkedOnRequest) {
		this.key = key;
		this.checkedOnRequest = checkedOnRequest;
	}

	/** The role permission key of this action, "till:" + the action in kebab case. */
	public String key() {
		return key;
	}

	/**
	 * Step D (3.1.0): the server checks the right on the request that does the action (the sale or pending save of a
	 * discount, the return, the close with a difference): a scan gives a grant for that request, and the pass is logged
	 * there. False for the customer list, which no single request does: its scan, or POST /badge/pass, is the pass.
	 */
	public boolean checkedOnRequest() {
		return checkedOnRequest;
	}
}
