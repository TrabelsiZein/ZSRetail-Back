package com.digithink.zsretail.core.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.dto.CreateUserRequestDTO;
import com.digithink.zsretail.core.dto.UserAccountDTO;
import com.digithink.zsretail.core.exception.RoleChangeRefusedException;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.AppRoleRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.security.SystemRoles;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class UserAccountService extends _BaseService<UserAccount, Long> {

	@Autowired
	private UserAccountRepository accountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private AppRoleRepository appRoleRepository;

	@Override
	protected _BaseRepository<UserAccount, Long> getRepository() {
		return accountRepository;
	}

	/**
	 * Get all users
	 */
	public List<UserAccountDTO> getAllUsers() {
		List<UserAccount> users = accountRepository.findAll();
		return users.stream()
				.map(user -> UserAccountDTO.fromEntity(user))
				.collect(Collectors.toList());
	}

	/**
	 * Get user by ID
	 */
	public UserAccountDTO getUserById(Long userId) {
		UserAccount user = findById(userId)
			.orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
		return UserAccountDTO.fromEntity(user);
	}

	/**
	 * Find user by username
	 */
	public Optional<UserAccount> findByUsername(String username) {
		return accountRepository.findByUsername(username);
	}

	/**
	 * Find user by badge code
	 */
	public Optional<UserAccount> findByBadgeCode(String badgeCode) {
		return accountRepository.findByBadgeCode(badgeCode);
	}

	/**
	 * Create a new user with role
	 */
	@Transactional
	public UserAccountDTO createUser(CreateUserRequestDTO request) throws Exception {
		// Check if username already exists
		if (accountRepository.findByUsername(request.getUsername()).isPresent()) {
			throw new RuntimeException("Username '" + request.getUsername() + "' already exists");
		}

		// Create new user
		UserAccount user = new UserAccount();
		user.setUsername(request.getUsername());
		user.setFullName(request.getFullName());
		user.setEmail(request.getEmail());
		user.setPassword(request.getPassword());
		user.setActive(true);

		// The user's role is his AppRole (step E: the enum Role is retired); a user without one cannot log in usefully
		if (request.getAppRoleId() == null) {
			throw new RuntimeException("A role is required");
		}
		applyAppRole(user, request.getAppRoleId());

		// Save user (password will be encrypted in save method)
		UserAccount savedUser = save(user);

		return UserAccountDTO.fromEntity(savedUser);
	}

	/** Assigns the given AppRole to a user: the source of his permissions, page and till rights. */
	public void applyAppRole(UserAccount user, Long appRoleId) {
		AppRole appRole = appRoleRepository.findById(appRoleId)
				.orElseThrow(() -> new RuntimeException("Role not found with id: " + appRoleId));
		user.setAppRole(appRole);
	}

	/**
	 * I-21: may {@code actor} give the AppRole {@code newAppRoleId} to {@code target} (a new user when its id is null)?
	 * Nothing to check when the role does not change (an edit sends the unchanged role back).
	 *
	 * @throws RoleChangeRefusedException with the message for the user
	 */
	public void checkRoleChange(UserAccount actor, UserAccount target, Long newAppRoleId) {
		AppRole newRole = appRoleRepository.findById(newAppRoleId)
				.orElseThrow(() -> new RuntimeException("Role not found with id: " + newAppRoleId));
		Long currentId = target.getAppRole() == null ? null : target.getAppRole().getId();
		checkRoleChange(actor, target, newRole, !newRole.getId().equals(currentId));
	}

	/**
	 * I-21, the two rules: nobody changes his own role; only an administrator gives or removes the Administrateur role
	 * (step E: the system role ADMIN, {@link SystemRoles}).
	 *
	 * @param changed whether the request changes the target's role (no check otherwise)
	 * @throws RoleChangeRefusedException with the message for the user
	 */
	public void checkRoleChange(UserAccount actor, UserAccount target, AppRole newRole, boolean changed) {
		if (!changed) {
			return;
		}
		if (actor != null && target.getId() != null && target.getId().equals(actor.getId())) {
			throw new RoleChangeRefusedException(RoleChangeRefusedException.OWN_ROLE);
		}
		boolean adminInvolved = SystemRoles.isAdminRole(newRole) || SystemRoles.isAdmin(target);
		if (adminInvolved && !SystemRoles.isAdmin(actor)) {
			throw new RoleChangeRefusedException(RoleChangeRefusedException.ADMIN_ONLY);
		}
	}

	/**
	 * Toggle user active status
	 */
	@Transactional
	public UserAccountDTO toggleUserStatus(Long userId) throws Exception {
		UserAccount user = findById(userId)
			.orElseThrow(() -> new RuntimeException("User not found with id: " + userId));
		
		user.setActive(!user.getActive());
		UserAccount savedUser = save(user);
		
		return UserAccountDTO.fromEntity(savedUser);
	}

	@Override
	public UserAccount save(UserAccount entity) throws Exception {
		try {
			String username = currentUserProvider.getCurrentUserName();

			if (entity.getId() == null) {
				entity.setCreatedBy(username);
				entity.setCreatedAt(LocalDateTime.now());
				// Only encode password if it's not already encoded
				if (entity.getPassword() != null && !entity.getPassword().startsWith("$2a$") && !entity.getPassword().startsWith("$2b$")) {
					entity.setPassword(passwordEncoder.encode(entity.getPassword()));
				}
			} else {
				entity.setUpdatedBy(username);
				entity.setUpdatedAt(LocalDateTime.now());
				// When updating, only encode password if it was changed
				if (entity.getPassword() != null && !entity.getPassword().startsWith("$2a$") && !entity.getPassword().startsWith("$2b$")) {
					entity.setPassword(passwordEncoder.encode(entity.getPassword()));
				}
			}

			return super.save(entity);
		} catch (Exception e) {
			log.error("Error saving user: {}", e.getMessage(), e);
			throw e;
		}
	}
}
