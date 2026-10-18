package com.digithink.zsretail.core.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.UserAccountRepository;

/**
 * Provides current authenticated user information
 */
public class CurrentUserProvider {

	@Autowired
	private UserAccountRepository userAccountRepository;

	/**
	 * Get the current authenticated user
	 */
	public UserAccount getCurrentUser() {
		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		String username;
		if (principal instanceof UserDetails) {
			username = ((UserDetails) principal).getUsername();
		} else {
			username = principal.toString();
		}
		return userAccountRepository.findByUsername(username)
				.orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
	}

	/**
	 * Get the current authenticated user's username
	 */
	public String getCurrentUserName() {
		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		String username;
		if (principal instanceof UserDetails) {
			username = ((UserDetails) principal).getUsername();
		} else {
			username = principal.toString();
		}
		return username;
	}

	/**
	 * Step S (3.1.0): whether the logged user's role ({@code UserAccount.appRole}) holds a permission key, e.g.
	 * {@code "read:admin-badge-scan-history"}: the same keys the frontend menu reads. False when no user is logged, the
	 * user has no role, or the role does not hold it.
	 */
	public boolean currentUserHasPermission(String permissionKey) {
		try {
			UserAccount user = getCurrentUser();
			return user != null && user.getAppRole() != null && user.getAppRole().getPermissions() != null
					&& user.getAppRole().getPermissions().contains(permissionKey);
		} catch (RuntimeException e) {
			return false;
		}
	}
}
