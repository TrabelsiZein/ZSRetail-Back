package com.digithink.zsretail.core.model.enumeration;

/**
 * The five till actions a badge authorises (customer list, return, line and total discount, closing a session with a
 * cash difference). The constant name is what the database stores (badge_scan_log.functionality,
 * user_account.badge_permissions) and what the till sends to POST /badge/scan; {@link #key()} is the permission key
 * of the role (app_role_permission) the rights move to at step C, served to the frontend by GET /config (tillActions,
 * step B).
 */
public enum BadgePermission {
	CONSULT_CUSTOMER_LIST("till:consult-customer-list"),
	MAKE_RETURN("till:make-return"),
	APPLY_LINE_DISCOUNT("till:apply-line-discount"),
	APPLY_TOTAL_DISCOUNT("till:apply-total-discount"),
	CLOSE_SESSION_WITH_DISCREPANCY("till:close-session-with-discrepancy");

	private final String key;

	BadgePermission(String key) {
		this.key = key;
	}

	/** The role permission key of this action, "till:" + the action in kebab case. */
	public String key() {
		return key;
	}
}
