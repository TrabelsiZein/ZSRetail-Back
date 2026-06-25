package com.digithink.pos.controller;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.digithink.pos.dto.CreateUserRequestDTO;
import com.digithink.pos.dto.UserAccountDTO;
import com.digithink.pos.model.UserAccount;
import com.digithink.pos.model.enumeration.BadgePermission;
import com.digithink.pos.model.enumeration.Role;
import com.digithink.pos.security.CurrentUserProvider;
import com.digithink.pos.service.BadgeService;
import com.digithink.pos.service.UserAccountService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("user-account")
@Log4j2
public class UserAccountAPI {

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private BadgeService badgeService;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@Autowired
	private com.digithink.pos.repository.UserAccountRepository accountRepository;

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
					m.put("role", u.getRole());
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

                UserAccountDTO user = UserAccountDTO.fromEntity(currentUser);
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
	@PostMapping
	public ResponseEntity<?> createUser(@RequestBody CreateUserRequestDTO request) {
		try {
			log.info("UserAccountAPI::createUser");

			// Validate request
			if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Username is required"));
			}
			if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Password is required"));
			}
			if (request.getRole() == null) {
				return ResponseEntity.badRequest().body(createErrorResponse("Role is required"));
			}

			UserAccountDTO createdUser = userAccountService.createUser(request);
			return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
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
		try {
			log.info("UserAccountAPI::updateUser: " + id);
			UserAccount user = userAccountService.findById(id)
					.orElseThrow(() -> new RuntimeException("User not found with id: " + id));

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
				}
			}
			if (request.containsKey("role")) {
				Object roleObj = request.get("role");
				if (roleObj instanceof String) {
					user.setRole(Role.valueOf((String) roleObj));
				} else if (roleObj instanceof Role) {
					user.setRole((Role) roleObj);
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
					// Validate badge code format
					if (!badgeService.validateBadgeCode(badgeCode)) {
						return ResponseEntity.badRequest().body(createErrorResponse("Invalid badge code format. Must be 6-50 alphanumeric characters."));
					}
					// Check uniqueness (excluding current user)
					java.util.Optional<UserAccount> existingUser = accountRepository.findByBadgeCode(badgeCode.trim());
					if (existingUser.isPresent() && !existingUser.get().getId().equals(user.getId())) {
						return ResponseEntity.badRequest().body(createErrorResponse("Badge code already exists"));
					}
					user.setBadgeCode(badgeCode.trim());
				} else {
					user.setBadgeCode(null);
				}
			}

			if (request.containsKey("badgePermissions")) {
				Object permissionsObj = request.get("badgePermissions");
				if (permissionsObj instanceof List) {
					@SuppressWarnings("unchecked")
					List<String> permissionsList = (List<String>) permissionsObj;
					Set<BadgePermission> permissions = permissionsList.stream()
							.map(p -> {
								try {
									return BadgePermission.valueOf(p);
								} catch (IllegalArgumentException e) {
									return null;
								}
							})
							.filter(p -> p != null)
							.collect(Collectors.toSet());
					user.setBadgePermissions(badgeService.badgePermissionsToString(permissions));
				} else if (permissionsObj instanceof String) {
					user.setBadgePermissions((String) permissionsObj);
				}
			}

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

			// Initialize badge permissions based on role if not set
			if (user.getBadgePermissions() == null || user.getBadgePermissions().trim().isEmpty()) {
				badgeService.initializeBadgePermissionsForRole(user);
			}

			UserAccount updatedUser = userAccountService.save(user);
			return ResponseEntity.ok(UserAccountDTO.fromEntity(updatedUser));
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
	 * Get user by badge code
	 * GET /user-account/by-badge/{badgeCode}
	 */
	@GetMapping("/by-badge/{badgeCode}")
	public ResponseEntity<?> getUserByBadgeCode(@PathVariable String badgeCode) {
		try {
			log.info("UserAccountAPI::getUserByBadgeCode: {}", badgeCode);
			
			java.util.Optional<UserAccount> userOpt = accountRepository.findByBadgeCode(badgeCode);
			if (!userOpt.isPresent()) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND)
						.body(createErrorResponse("User not found with badge code: " + badgeCode));
			}

			return ResponseEntity.ok(UserAccountDTO.fromEntity(userOpt.get()));
		} catch (Exception e) {
			log.error("UserAccountAPI::getUserByBadgeCode:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get user's badge info
	 * GET /user-account/{id}/badge
	 */
	@GetMapping("/{id}/badge")
	public ResponseEntity<?> getUserBadge(@PathVariable Long id) {
		try {
			log.info("UserAccountAPI::getUserBadge: {}", id);
			UserAccount user = userAccountService.findById(id)
					.orElseThrow(() -> new RuntimeException("User not found with id: " + id));

			Map<String, Object> response = new HashMap<>();
			response.put("badgeCode", user.getBadgeCode());
			response.put("badgePermissions", user.getBadgePermissions());
			response.put("badgeExpirationDate", user.getBadgeExpirationDate());
			response.put("badgeRevoked", user.getBadgeRevoked());
			response.put("badgeRevokedAt", user.getBadgeRevokedAt());
			response.put("badgeRevokeReason", user.getBadgeRevokeReason());
			response.put("isExpired", badgeService.isBadgeExpired(user));
			response.put("isRevoked", badgeService.isBadgeRevoked(user));

			return ResponseEntity.ok(response);
		} catch (RuntimeException e) {
			log.error("UserAccountAPI::getUserBadge:error: {}", e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("UserAccountAPI::getUserBadge:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Update user badge
	 * PUT /user-account/{id}/badge
	 */
	@PutMapping("/{id}/badge")
	public ResponseEntity<?> updateUserBadge(@PathVariable Long id, @RequestBody Map<String, Object> request) {
		try {
			log.info("UserAccountAPI::updateUserBadge: {}", id);
			UserAccount user = userAccountService.findById(id)
					.orElseThrow(() -> new RuntimeException("User not found with id: " + id));

			if (request.containsKey("badgeCode")) {
				String badgeCode = (String) request.get("badgeCode");
				if (badgeCode != null && !badgeCode.trim().isEmpty()) {
					if (!badgeService.validateBadgeCode(badgeCode)) {
						return ResponseEntity.badRequest()
								.body(createErrorResponse("Invalid badge code format. Must be 6-50 alphanumeric characters."));
					}
					java.util.Optional<UserAccount> existingUser = accountRepository.findByBadgeCode(badgeCode.trim());
					if (existingUser.isPresent() && !existingUser.get().getId().equals(user.getId())) {
						return ResponseEntity.badRequest().body(createErrorResponse("Badge code already exists"));
					}
					user.setBadgeCode(badgeCode.trim());
				} else {
					user.setBadgeCode(null);
				}
			}

			if (request.containsKey("badgePermissions")) {
				Object permissionsObj = request.get("badgePermissions");
				if (permissionsObj instanceof List) {
					@SuppressWarnings("unchecked")
					List<String> permissionsList = (List<String>) permissionsObj;
					Set<BadgePermission> permissions = permissionsList.stream()
							.map(p -> {
								try {
									return BadgePermission.valueOf(p);
								} catch (IllegalArgumentException e) {
									return null;
								}
							})
							.filter(p -> p != null)
							.collect(Collectors.toSet());
					user.setBadgePermissions(badgeService.badgePermissionsToString(permissions));
				} else if (permissionsObj instanceof String) {
					user.setBadgePermissions((String) permissionsObj);
				}
			}

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
			return ResponseEntity.ok(UserAccountDTO.fromEntity(updatedUser));
		} catch (RuntimeException e) {
			log.error("UserAccountAPI::updateUserBadge:error: {}", e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("UserAccountAPI::updateUserBadge:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Generate badge code for user
	 * POST /user-account/{id}/badge/generate
	 */
	@PostMapping("/{id}/badge/generate")
	public ResponseEntity<?> generateBadgeCode(@PathVariable Long id) {
		try {
			log.info("UserAccountAPI::generateBadgeCode: {}", id);
			UserAccount user = userAccountService.findById(id)
					.orElseThrow(() -> new RuntimeException("User not found with id: " + id));

			String badgeCode = badgeService.generateBadgeCode(user);
			user.setBadgeCode(badgeCode);
			
			// Initialize permissions based on role if not set
			if (user.getBadgePermissions() == null || user.getBadgePermissions().trim().isEmpty()) {
				badgeService.initializeBadgePermissionsForRole(user);
			}

			UserAccount updatedUser = userAccountService.save(user);
			
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
		try {
			log.info("UserAccountAPI::revokeBadge: {}", id);
			UserAccount user = userAccountService.findById(id)
					.orElseThrow(() -> new RuntimeException("User not found with id: " + id));

			String reason = request.get("reason");
			UserAccount revokedBy = currentUserProvider.getCurrentUser();

			badgeService.revokeBadge(user, revokedBy, reason);

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
		try {
			log.info("UserAccountAPI::reactivateBadge: {}", id);
			UserAccount user = userAccountService.findById(id)
					.orElseThrow(() -> new RuntimeException("User not found with id: " + id));

			badgeService.reactivateBadge(user);

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

