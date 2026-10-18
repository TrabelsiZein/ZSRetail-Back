package com.digithink.zsretail.store.controller;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.BadgeService;
import com.digithink.zsretail.store.service.BadgeScanLogService;

import lombok.extern.log4j.Log4j2;

/**
 * BadgeAPI - REST controller for badge-related operations
 */
@RestController
@RequestMapping("badge")
@Log4j2
public class BadgeAPI {

	@Autowired
	private BadgeService badgeService;

	@Autowired
	private BadgeScanLogService badgeScanLogService;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	/**
	 * Check if current user is admin (the one definition: {@link CurrentUserProvider#currentUserIsAdmin()})
	 */
	private boolean isAdmin() {
		return currentUserProvider.currentUserIsAdmin();
	}

	/**
	 * Scan badge and log
	 * POST /badge/scan
	 */
	@PostMapping("/scan")
	public ResponseEntity<?> scanBadge(@RequestBody Map<String, Object> request) {
		try {
			log.info("BadgeAPI::scanBadge");
			
			String badgeCode = (String) request.get("badgeCode");
			String permissionStr = (String) request.get("permission");
			Long sessionId = request.get("sessionId") != null ? 
					Long.parseLong(request.get("sessionId").toString()) : null;

			if (badgeCode == null || badgeCode.trim().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Badge code is required"));
			}
			if (permissionStr == null || permissionStr.trim().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Permission is required"));
			}

			BadgePermission permission;
			try {
				permission = BadgePermission.valueOf(permissionStr);
			} catch (IllegalArgumentException e) {
				return ResponseEntity.badRequest().body(createErrorResponse("Invalid permission: " + permissionStr));
			}

			UserAccount scannedBy = currentUserProvider.getCurrentUser();
			BadgeService.BadgeScanResult result = badgeService.scanBadge(badgeCode, permission, scannedBy, sessionId);

			Map<String, Object> response = new HashMap<>();
			response.put("success", result.isSuccess());
			response.put("hasPermission", result.isHasPermission());
			response.put("failureReason", result.getFailureReason());
			
			if (result.getScannedUser() != null) {
				response.put("user", createUserResponse(result.getScannedUser()));
			}

			if (result.isSuccess()) {
				return ResponseEntity.ok(response);
			} else {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
			}
		} catch (Exception e) {
			log.error("BadgeAPI::scanBadge:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get badge scan history with filters
	 * GET /badge/scan-history
	 */
	@GetMapping("/scan-history")
	public ResponseEntity<?> getScanHistory(
			@RequestParam(required = false) String badgeCode,
			@RequestParam(required = false) Long userId,
			@RequestParam(required = false) String permission,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
			@RequestParam(required = false) Boolean success,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		try {
			log.info("BadgeAPI::getScanHistory");
			
//			if (!isAdmin()) {
//				return ResponseEntity.status(HttpStatus.FORBIDDEN)
//						.body(createErrorResponse("Only administrators can access scan history"));
//			}

			BadgePermission permissionEnum = null;
			if (permission != null && !permission.trim().isEmpty()) {
				try {
					permissionEnum = BadgePermission.valueOf(permission);
				} catch (IllegalArgumentException e) {
					return ResponseEntity.badRequest().body(createErrorResponse("Invalid permission: " + permission));
				}
			}

			Page<BadgeScanLog> scanLogs = badgeScanLogService.getScanHistoryPaginated(
					badgeCode, userId, permissionEnum, fromDate, toDate, success, page, size);

			return ResponseEntity.ok(scanLogs);
		} catch (Exception e) {
			log.error("BadgeAPI::getScanHistory:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get scan history for specific badge
	 * GET /badge/{badgeCode}/scan-history
	 */
	@GetMapping("/{badgeCode}/scan-history")
	public ResponseEntity<?> getBadgeScanHistory(
			@PathVariable String badgeCode,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
			@RequestParam(required = false) Boolean success,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		try {
			log.info("BadgeAPI::getBadgeScanHistory: {}", badgeCode);
			
//			if (!isAdmin()) {
//				return ResponseEntity.status(HttpStatus.FORBIDDEN)
//						.body(createErrorResponse("Only administrators can access scan history"));
//			}

			Page<BadgeScanLog> scanLogs = badgeScanLogService.getScanHistoryPaginated(
					badgeCode, null, null, fromDate, toDate, success, page, size);

			return ResponseEntity.ok(scanLogs);
		} catch (Exception e) {
			log.error("BadgeAPI::getBadgeScanHistory:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get scan statistics
	 * GET /badge/scan-statistics
	 */
	@GetMapping("/scan-statistics")
	public ResponseEntity<?> getScanStatistics(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate) {
		try {
			log.info("BadgeAPI::getScanStatistics");
			
//			if (!isAdmin()) {
//				return ResponseEntity.status(HttpStatus.FORBIDDEN)
//						.body(createErrorResponse("Only administrators can access statistics"));
//			}

			Map<String, Object> statistics = badgeScanLogService.getScanStatistics(fromDate, toDate);
			return ResponseEntity.ok(statistics);
		} catch (Exception e) {
			log.error("BadgeAPI::getScanStatistics:error: {}", e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	// Helper methods
	private String getDetailedMessage(Exception e) {
		return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
	}

	private Map<String, String> createErrorResponse(String message) {
		Map<String, String> response = new HashMap<>();
		response.put("error", message);
		return response;
	}

	private Map<String, Object> createUserResponse(UserAccount user) {
		Map<String, Object> response = new HashMap<>();
		response.put("id", user.getId());
		response.put("username", user.getUsername());
		response.put("fullName", user.getFullName());
		response.put("badgeCode", user.getBadgeCode());
		return response;
	}
}

