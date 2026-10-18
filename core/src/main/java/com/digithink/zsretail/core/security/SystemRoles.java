package com.digithink.zsretail.core.security;

import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;

/**
 * Step E (3.1.0): the roles the application knows by name, the one definition of an administrator. The user's role is
 * his AppRole (app_role); the enum Role and the column user_account.role it filled are retired. An administrator is a
 * user of the system role named {@link #ADMIN} (the role the seed creates and the 2.2.2 enum ADMIN mapped to); a custom
 * role is never one, whatever its permissions.
 */
public final class SystemRoles {

	/** The administrator role: every page, the ERP admin pages, the only one giving or removing this role. */
	public static final String ADMIN = "ADMIN";
	/** The store manager role of the seed. */
	public static final String RESPONSIBLE = "RESPONSIBLE";
	/** The cashier role of the seed. */
	public static final String POS_USER = "POS_USER";

	private SystemRoles() {
	}

	/** The role is the system administrator role (by its name; false for null). */
	public static boolean isAdminRole(AppRole role) {
		return role != null && ADMIN.equals(role.getName());
	}

	/** The user is an administrator: his role is the system administrator role (false for null or no role). */
	public static boolean isAdmin(UserAccount user) {
		return user != null && isAdminRole(user.getAppRole());
	}
}
