package com.digithink.zsretail.core.exception;

/**
 * I-21 (test session 2026-10-17): a role change the logged user may not make, with the message shown to him. Thrown by
 * {@code UserAccountService.checkRoleChange}; the users API answers 403 with the message.
 */
public class RoleChangeRefusedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/** Nobody changes the role of his own account. */
	public static final String OWN_ROLE = "Vous ne pouvez pas modifier votre propre rôle.";

	/** Only an administrator gives or removes the Administrateur role. */
	public static final String ADMIN_ONLY = "Seul un administrateur peut attribuer ou retirer le rôle Administrateur.";

	public RoleChangeRefusedException(String message) {
		super(message);
	}
}
