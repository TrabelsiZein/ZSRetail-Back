package com.digithink.zsretail.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.BadgeScanLog;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.BadgePermission;
import com.digithink.zsretail.repository.BadgeScanLogRepository;
import com.digithink.zsretail.repository._BaseRepository;

import lombok.extern.log4j.Log4j2;

/**
 * BadgeScanLogService - handles badge scan log operations
 */
@Service
@Log4j2
public class BadgeScanLogService extends _BaseService<BadgeScanLog, Long> {

	@Autowired
	private BadgeScanLogRepository badgeScanLogRepository;

	@Override
	protected _BaseRepository<BadgeScanLog, Long> getRepository() {
		return badgeScanLogRepository;
	}

	/**
	 * Log badge scan attempt
	 */
	public BadgeScanLog logScan(BadgeScanLog badgeScanLog) {
		try {
			return badgeScanLogRepository.save(badgeScanLog);
		} catch (Exception e) {
			log.error("Error logging badge scan: {}", e.getMessage(), e);
			throw e;
		}
	}

	/**
	 * Get scan history for user
	 */
	public List<BadgeScanLog> getScanHistory(UserAccount user, LocalDateTime from, LocalDateTime to) {
		if (user == null) {
			return List.of();
		}
		return badgeScanLogRepository.findByScannedBy(user);
	}

	/**
	 * Get scan history for specific badge
	 */
	public List<BadgeScanLog> getScanHistoryByBadge(String badgeCode, LocalDateTime from, LocalDateTime to) {
		if (badgeCode == null || badgeCode.trim().isEmpty()) {
			return List.of();
		}
		if (from != null && to != null) {
			return badgeScanLogRepository.findByScannedBadgeCodeAndTimestampBetween(badgeCode, from, to);
		}
		return badgeScanLogRepository.findByScannedBadgeCode(badgeCode);
	}

	/**
	 * Get paginated scan history with filters
	 */
	public Page<BadgeScanLog> getScanHistoryPaginated(String badgeCode, Long userId, BadgePermission permission,
			LocalDateTime fromDate, LocalDateTime toDate, Boolean success, int page, int size) {
		Pageable pageable = PageRequest.of(page, size);
		return badgeScanLogRepository.findWithFilters(badgeCode, userId, permission, fromDate, toDate, success,
				pageable);
	}

	/**
	 * Get scan statistics
	 */
	public Map<String, Object> getScanStatistics(LocalDateTime fromDate, LocalDateTime toDate) {
		Map<String, Object> stats = new HashMap<>();

		// Total scans
		Long totalScans = badgeScanLogRepository.countScansInPeriod(fromDate, toDate);
		stats.put("totalScans", totalScans != null ? totalScans : 0L);

		// Successful scans
		Long successfulScans = badgeScanLogRepository.countSuccessfulScansInPeriod(fromDate, toDate);
		stats.put("successfulScans", successfulScans != null ? successfulScans : 0L);

		// Success rate
		if (totalScans != null && totalScans > 0) {
			double successRate = (successfulScans != null ? successfulScans.doubleValue() : 0.0) / totalScans * 100;
			stats.put("successRate", Math.round(successRate * 100.0) / 100.0);
		} else {
			stats.put("successRate", 0.0);
		}

		// Get all scans in period for detailed statistics
		List<BadgeScanLog> allScans = badgeScanLogRepository
				.findWithFilters(null, null, null, fromDate, toDate, null, PageRequest.of(0, Integer.MAX_VALUE))
				.getContent();

		// Most scanned badges
		Map<String, Long> badgeCounts = allScans.stream()
				.collect(Collectors.groupingBy(BadgeScanLog::getScannedBadgeCode, Collectors.counting()));
		List<Map<String, Object>> topBadges = badgeCounts.entrySet().stream()
				.sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue())).limit(10).map(e -> {
					Map<String, Object> badgeStat = new HashMap<>();
					badgeStat.put("badgeCode", e.getKey());
					badgeStat.put("scanCount", e.getValue());
					return badgeStat;
				}).collect(Collectors.toList());
		stats.put("topBadges", topBadges);

		// Most accessed permissions
		Map<BadgePermission, Long> permissionCounts = allScans.stream()
				.collect(Collectors.groupingBy(BadgeScanLog::getFunctionality, Collectors.counting()));
		List<Map<String, Object>> topPermissions = permissionCounts.entrySet().stream()
				.sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue())).limit(10).map(e -> {
					Map<String, Object> permStat = new HashMap<>();
					permStat.put("permission", e.getKey().name());
					permStat.put("scanCount", e.getValue());
					return permStat;
				}).collect(Collectors.toList());
		stats.put("topPermissions", topPermissions);

		// Failure reasons breakdown
		Map<String, Long> failureReasons = allScans.stream().filter(scan -> !scan.getSuccess())
				.filter(scan -> scan.getFailureReason() != null)
				.collect(Collectors.groupingBy(BadgeScanLog::getFailureReason, Collectors.counting()));
		stats.put("failureReasons", failureReasons);

		return stats;
	}

	/**
	 * Export scan history (returns list for export)
	 */
	public List<BadgeScanLog> exportScanHistory(String badgeCode, LocalDateTime fromDate, LocalDateTime toDate) {
		if (badgeCode != null && !badgeCode.trim().isEmpty()) {
			if (fromDate != null && toDate != null) {
				return badgeScanLogRepository.findByScannedBadgeCodeAndTimestampBetween(badgeCode, fromDate, toDate);
			}
			return badgeScanLogRepository.findByScannedBadgeCode(badgeCode);
		}

		// Get all scans in period
		return badgeScanLogRepository
				.findWithFilters(null, null, null, fromDate, toDate, null, PageRequest.of(0, Integer.MAX_VALUE))
				.getContent();
	}
}
