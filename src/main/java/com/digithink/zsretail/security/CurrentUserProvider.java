package com.digithink.zsretail.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.Role;
import com.digithink.zsretail.repository.UserAccountRepository;

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
	 * 2.2.2: the one definition of an admin on the server: the logged user's role is {@link Role#ADMIN}
	 * ({@code UserAccount.role}, kept in step with the built-in role ADMIN by {@code UserAccountService.applyAppRole}; a
	 * custom role is never ADMIN). An admin is never asked for a badge. False when no user is logged (or not found).
	 */
	public boolean currentUserIsAdmin() {
		try {
			UserAccount user = getCurrentUser();
			return user != null && user.getRole() == Role.ADMIN;
		} catch (RuntimeException e) {
			return false;
		}
	}
}
