package com.digithink.zsretail.core.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.CreateUserRequestDTO;
import com.digithink.zsretail.core.dto.UserAccountDTO;
import com.digithink.zsretail.core.exception.RoleChangeRefusedException;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.BadgeService;
import com.digithink.zsretail.core.service.UserAccountService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;

/**
 * The users, behind the permissions of the Users pages: without them any logged user read every badge code, set any password
 * and generated any badge, the administrator's included. Two calls stay open to any logged user: /cashiers (names, for the
 * history filters) and /current (one's own account).
 */
@RestController
@RequestMapping("user-account")
@Log4j2
public class UserAccountAPI {

	static final String READ_USERS = "read:admin-users";
	static final String WRITE_USERS = "write:admin-users";
	static final String DELETE_USERS = "delete:admin-users";

	/** The head office's Users page has this one key, for reading and for its actions. */
	static final String HEAD_OFFICE_USERS = "read:admin-headoffice-users";

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private BadgeService badgeService;

	/** 3.4.0, event log step 9: the administrator's gestures (absent in the hand-built tests: nothing written). */
	@Autowired(required = false)
	private com.digithink.zsretail.core.eventlog.service.AdminEvents adminEvents;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@Autowired
	private ApplicationModeService applicationModeService;

	@Autowired
	private com.digithink.zsretail.core.repository.UserAccountRepository accountRepository;

	/** The 403 of a logged user whose role lacks the key; null when it holds it. */
	private ResponseEntity<?> refusedWithout(String key) {
		if (currentUserProvider.currentUserHasPermission(key) || (applicationModeService.isHeadOffice()
				&& currentUserProvider.currentUserHasPermission(HEAD_OFFICE_USERS))) {
			return null;
		}
		log.warn("UserAccountAPI: refused without the right {}", key);
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(createErrorResponse("The users need the right " + key + "."));
	}

	/**
	 * Get minimal cashier list (id + names) — accessible to ADMIN and RESPONSIBLE for filter dropdowns
	 */
	@GetMapping("/cashiers")
	public ResponseEntity<?> getCashierList() {
		try {
			List<Map<String, Object>> result = accountRepository.findAll().stream()
				.filter(u -> !Boolean.FALSE.equals(u.getActive()))
				.map(u -> {
					Map<String, Object> m = new HashMap<>();
					m.put("id", u.getId());
					m.put("username", u.getUsername());
					m.put("fullName", u.getFullName());
					m.put("appRoleName", u.getAppRole() != null ? u.getAppRole().getName() : null); // step E: no enum
					return m;
				})
				.collect(Collectors.toList());
			return ResponseEntity.ok(result);
		} catch (Exception e) {
			log.error("UserAccountAPI::getCashierList:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(e.getMessage()));
		}
	}

	/**
	 * Get all users (admin only)
	 */
	@GetMapping
	public ResponseEntity<?> getAllUsers() {
		ResponseEntity<?> refused = refusedWithout(READ_USERS);
		if (refused != null) {
			return refused;
		}
		try {
			log.info("UserAccountAPI::getAllUsers");
			return ResponseEntity.ok(userAccountService.getAllUsers());
		} catch (Exception e) {
			log.error("UserAccountAPI::getAllUsers:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get user by ID (admin only)
	 */
        @GetMapping("/{id}")
        public ResponseEntity<?> getUserById(@PathVariable Long id) {
            ResponseEntity<?> refused = refusedWithout(READ_USERS);
            if (refused != null) {
                return refused;
            }
            try {
                log.info("UserAccountAPI::getUserById: " + id);
                UserAccountDTO user = userAccountService.getUserById(id);
                return ResponseEntity.ok(user);
            } catch (Exception e) {
                log.error("UserAccountAPI::getUserById:error: " + e.getMessage(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(createErrorResponse(getDetailedMessage(e)));
            }
        }

        @GetMapping("/current")
        public ResponseEntity<?> getCurrentUser() {
            try {
                log.info("UserAccountAPI::getCurrentUser");
                
                UserAccount currentUser = currentUserProvider.getCurrentUser();
                if (currentUser == null) {
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorResponse("User not authenticated"));
                }

                UserAccountDTO user = UserAccountDTO.ownAccount(currentUser);
                return ResponseEntity.ok(user);
            } catch (Exception e) {
                log.error("UserAccountAPI::getCurrentUser:error: " + e.getMessage(), e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(createErrorResponse(getDetailedMessage(e)));
            }
        }

	/**
	 * Create new user (admin only)
	 */
	/**
	 * The refusal of a badge code for the user {@code selfId} (null: a new user), or null when it may be given (none, or a
	 * valid code no other user holds). The add popup and the edit popup check it alike.
	 */
	private String badgeRefusal(String badgeCode, Long selfId) {
		if (badgeCode == null || badgeCode.trim().isEmpty()) {
			return null;
		}
		if (!badgeService.validateBadgeCode(badgeCode)) {
			return "Invalid badge code format. Must be 6-50 alphanumeric characters.";
		}
		java.util.Optional<UserAccount> holder = accountRepository.findByBadgeCode(badgeCode.trim());
		if (holder.isPresent() && !holder.get().getId().equals(selfId)) {
			return "Badge code already exists";
		}
		return null;
	}

	@PostMapping
	public ResponseEntity<?> createUser(@RequestBody CreateUserRequestDTO request) {
		ResponseEntity<?> refused = refusedWithout(WRITE_USERS);
		if (refused != null) {
			return refused;
		}
		try {
			log.info("UserAccountAPI::createUser");

			// Validate request
			if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Username is required"));
			}
			if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Password is required"));
			}
			if (request.getAppRoleId() == null) {
				return ResponseEntity.badRequest().body(createErrorResponse("Role is required"));
			}
			// 3.4.0: the badge typed in the add popup, checked as an update checks it (it was dropped)
			String badgeRefused = badgeRefusal(request.getBadgeCode(), null);
			if (badgeRefused != null) {
				return ResponseEntity.badRequest().body(createErrorResponse(badgeRefused));
			}
			if (request.getBadgeExpirationDate() != null && !request.getBadgeExpirationDate().trim().isEmpty()) {
				try {
					LocalDateTime.parse(request.getBadgeExpirationDate().trim());
				} catch (java.time.format.DateTimeParseException e) {
					return ResponseEntity.badRequest().body(createErrorResponse("Invalid badge expiration date."));
				}
			}

			// I-21: only an administrator creates an Administrateur
			userAccountService.checkRoleChange(currentUserProvider.getCurrentUser(), new UserAccount(),
					request.getAppRoleId());

			UserAccountDTO createdUser = userAccountService.createUser(request);
			return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
		} catch (RoleChangeRefusedException e) {
			log.warn("UserAccountAPI::createUser:refused: {}", e.getMessage());
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(createErrorResponse(e.getMessage()));
		} catch (RuntimeException e) {
			log.error("UserAccountAPI::createUser:error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("UserAccountAPI::createUser:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Update user (admin only)
	 */
	@PutMapping("/{id}")
	public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody Map<String, Object> request) {
		ResponseEntity<?> refused = refusedWithout(WRITE_USERS);
		if (refused != null) {
			return refused;
		}
		try {
			log.info("UserAccountAPI::updateUser: " + id);
			UserAccount user = userAccountService.findById(id)
					.orElseThrow(() -> new RuntimeException("User not found with id: " + id));
			// 3.4.0, event log step 9: the values before (the loaded user is changed in place)
			Map<String, String> before = com.digithink.zsretail.core.eventlog.service.AdminEvents.describe(user);
			boolean passwordSet = false;

			// Update fields if provided
			if (request.containsKey("fullName")) {
				user.setFullName((String) request.get("fullName"));
			}
			if (request.containsKey("email")) {
				user.setEmail((String) request.get("email"));
			}
			if (request.containsKey("password")) {
				String password = (String) request.get("password");
				if (password != null && !password.trim().isEmpty()) {
					user.setPassword(password);
					passwordSet = true;
				}
			}
			if (request.containsKey("appRoleId")) {
				Object appRoleObj = request.get("appRoleId");
				Long appRoleId = null;
				if (appRoleObj instanceof Number) {
					appRoleId = ((Number) appRoleObj).longValue();
				} else if (appRoleObj instanceof String && !((String) appRoleObj).trim().isEmpty()) {
					appRoleId = Long.valueOf(((String) appRoleObj).trim());
				}
				if (appRoleId != null) {
					// I-21: not one's own role; the Administrateur role given or removed by an administrator only
					userAccountService.checkRoleChange(currentUserProvider.getCurrentUser(), user, appRoleId);
					// Sets the AppRole: the user's role (step E: the enum Role and its "role" field are retired)
					userAccountService.applyAppRole(user, appRoleId);
				}
			}
			if (request.containsKey("active")) {
				Object activeObj = request.get("active");
				if (activeObj instanceof Boolean) {
					user.setActive((Boolean) activeObj);
				} else if (activeObj instanceof String) {
					user.setActive(Boolean.parseBoolean((String) activeObj));
				}
			}

			// Badge fields
			if (request.containsKey("badgeCode")) {
				String badgeCode = (String) request.get("badgeCode");
				if (badgeCode != null && !badgeCode.trim().isEmpty()) {
					String badgeRefused = badgeRefusal(badgeCode, user.getId());
					if (badgeRefused != null) {
						return ResponseEntity.badRequest().body(createErrorResponse(badgeRefused));
					}
					user.setBadgeCode(badgeCode.trim());
				} else {
					user.setBadgeCode(null);
				}
			}

			// Step C (3.1.0): no badge permission list any more (the till rights are the role's); one sent is ignored
			if (request.containsKey("badgeExpirationDate")) {
				Object expirationObj = request.get("badgeExpirationDate");
				if (expirationObj == null || expirationObj.toString().trim().isEmpty()) {
					user.setBadgeExpirationDate(null);
				} else if (expirationObj instanceof String) {
					try {
						user.setBadgeExpirationDate(LocalDateTime.parse((String) expirationObj));
					} catch (Exception e) {
						log.warn("Invalid badge expiration date format: {}", expirationObj);
					}
				}
			}

			UserAccount updatedUser = userAccountService.save(user);
			if (adminEvents != null) adminEvents.userUpdated(before, updatedUser, passwordSet); // 3.4.0, event log step 9
			return ResponseEntity.ok(UserAccountDTO.fromEntity(updatedUser));
		} catch (RoleChangeRefusedException e) {
			log.warn("UserAccountAPI::updateUser:refused: {}", e.getMessage());
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(createErrorResponse(e.getMessage()));
		} catch (RuntimeException e) {
			log.error("UserAccountAPI::updateUser:error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("UserAccountAPI::updateUser:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Delete user (admin only)
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteUser(@PathVariable Long id) {
		ResponseEntity<?> refused = refusedWithout(DELETE_USERS);
		if (refused != null) {
			return refused;
		}
		try {
			log.info("UserAccountAPI::deleteUser: " + id);
			UserAccount user = userAccountService.findById(id)
					.orElseThrow(() -> new RuntimeException("User not found with id: " + id));

			// Prevent deleting yourself
			UserAccount currentUser = currentUserProvider.getCurrentUser();
			if (user.getId().equals(currentUser.getId())) {
				return ResponseEntity.badRequest().body(createErrorResponse("You cannot delete your own account"));
			}

			userAccountService.deleteById(id);
			if (adminEvents != null) adminEvents.userDeleted(user); // 3.4.0, event log step 9
			return ResponseEntity.ok().body(createSuccessResponse("User deleted successfully"));
		} catch (RuntimeException e) {
			log.error("UserAccountAPI::deleteUser:error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("UserAccountAPI::deleteUser:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Toggle user active status (admin only)
	 */
	@PutMapping("/{id}/toggle-status")
	public ResponseEntity<?> toggleUserStatus(@PathVariable Long id) {
		ResponseEntity<?> refused = refusedWithout(WRITE_USERS);
		if (refused != null) {
			return refused;
		}
		try {
			log.info("UserAccountAPI::toggleUserStatus: " + id);
			UserAccountDTO updatedUser = userAccountService.toggleUserStatus(id);
			return ResponseEntity.ok(updatedUser);
		} catch (RuntimeException e) {
			log.error("UserAccountAPI::toggleUserStatus:error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("UserAccountAPI::toggleUserStatus:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	// Helper methods from _BaseController
	private String getDetailedMessage(Exception e) {
		return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
	}

	private Map<String, String> createErrorResponse(String message) {
		return java.util.Map.of("error", message);
	}

	private Map<String, String> createSuccessResponse(String message) {
		return java.util.Map.of("message", message);
	}

	/**
	 * Generate badge code for user
	 * POST /user-account/{id}/badge/generate
	 */
	@PostMapping("/{id}/badge/generate")
	public ResponseEntity<?> generateBadgeCode(@PathVariable Long id) {
		ResponseEntity<?> refused = refusedWithout(WRITE_USERS);
		if (refused != null) {
			return refused;
		}
		try {
			log.info("UserAccountAPI::generateBadgeCode: {}", id);
			UserAccount user = userAccountService.findById(id)
					.orElseThrow(() -> new RuntimeException("User not found with id: " + id));

			boolean hadBadge = user.getBadgeCode() != null && !user.getBadgeCode().trim().isEmpty(); // 3.4.0
			String badgeCode = badgeService.generateBadgeCode(user);
			user.setBadgeCode(badgeCode);

			UserAccount updatedUser = userAccountService.save(user);
			if (adminEvents != null) adminEvents.badgeGenerated(updatedUser, hadBadge); // 3.4.0, event log step 9
			
			Map<String, Object> response = new HashMap<>();
			response.put("badgeCode", badgeCode);
			response.put("user", UserAccountDTO.fromEntity(updatedUser));
			
			return ResponseEntity.ok(response);
		} catch (RuntimeException e) {
			log.error("UserAccountAPI::generateBadgeCode:error: {}", e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("UserAccountAPI::generateBadgeCode:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Revoke badge
	 * POST /user-account/{id}/badge/revoke
	 */
	@PostMapping("/{id}/badge/revoke")
	public ResponseEntity<?> revokeBadge(@PathVariable Long id, @RequestBody Map<String, String> request) {
		ResponseEntity<?> refused = refusedWithout(WRITE_USERS);
		if (refused != null) {
			return refused;
		}
		try {
			log.info("UserAccountAPI::revokeBadge: {}", id);
			UserAccount user = userAccountService.findById(id)
					.orElseThrow(() -> new RuntimeException("User not found with id: " + id));

			String reason = request.get("reason");
			UserAccount revokedBy = currentUserProvider.getCurrentUser();

			badgeService.revokeBadge(user, revokedBy, reason);
			if (adminEvents != null) adminEvents.badgeRevoked(user, reason); // 3.4.0, event log step 9

			return ResponseEntity.ok(UserAccountDTO.fromEntity(user));
		} catch (RuntimeException e) {
			log.error("UserAccountAPI::revokeBadge:error: {}", e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("UserAccountAPI::revokeBadge:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Reactivate revoked badge
	 * POST /user-account/{id}/badge/reactivate
	 */
	@PostMapping("/{id}/badge/reactivate")
	public ResponseEntity<?> reactivateBadge(@PathVariable Long id) {
		ResponseEntity<?> refused = refusedWithout(WRITE_USERS);
		if (refused != null) {
			return refused;
		}
		try {
			log.info("UserAccountAPI::reactivateBadge: {}", id);
			UserAccount user = userAccountService.findById(id)
					.orElseThrow(() -> new RuntimeException("User not found with id: " + id));

			String formerReason = user.getBadgeRevokeReason(); // 3.4.0: wiped by the reactivation
			badgeService.reactivateBadge(user);
			if (adminEvents != null) adminEvents.badgeReactivated(user, formerReason); // 3.4.0, event log step 9

			return ResponseEntity.ok(UserAccountDTO.fromEntity(user));
		} catch (RuntimeException e) {
			log.error("UserAccountAPI::reactivateBadge:error: {}", e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("UserAccountAPI::reactivateBadge:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}

