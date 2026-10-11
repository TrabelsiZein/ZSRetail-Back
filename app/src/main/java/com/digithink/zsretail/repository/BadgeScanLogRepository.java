package com.digithink.zsretail.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.model.BadgeScanLog;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.BadgePermission;

public interface BadgeScanLogRepository extends _BaseRepository<BadgeScanLog, Long> {

	List<BadgeScanLog> findByScannedBy(UserAccount scannedBy);

	List<BadgeScanLog> findByScannedBadgeCode(String scannedBadgeCode);

	List<BadgeScanLog> findByScannedBadgeCodeAndTimestampBetween(String scannedBadgeCode, LocalDateTime from, LocalDateTime to);

	Page<BadgeScanLog> findByScannedBadgeCodeAndTimestampBetween(String scannedBadgeCode, LocalDateTime from, LocalDateTime to, Pageable pageable);

	@Query("SELECT b FROM BadgeScanLog b WHERE " +
			"(:badgeCode IS NULL OR b.scannedBadgeCode = :badgeCode) AND " +
			"(:userId IS NULL OR b.scannedBy.id = :userId) AND " +
			"(:permission IS NULL OR b.functionality = :permission) AND " +
			"(:fromDate IS NULL OR b.timestamp >= :fromDate) AND " +
			"(:toDate IS NULL OR b.timestamp <= :toDate) AND " +
			"(:success IS NULL OR b.success = :success)")
	Page<BadgeScanLog> findWithFilters(
			@Param("badgeCode") String badgeCode,
			@Param("userId") Long userId,
			@Param("permission") BadgePermission permission,
			@Param("fromDate") LocalDateTime fromDate,
			@Param("toDate") LocalDateTime toDate,
			@Param("success") Boolean success,
			Pageable pageable);

	@Query("SELECT COUNT(b) FROM BadgeScanLog b WHERE " +
			"(:fromDate IS NULL OR b.timestamp >= :fromDate) AND " +
			"(:toDate IS NULL OR b.timestamp <= :toDate)")
	Long countScansInPeriod(@Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);

	@Query("SELECT COUNT(b) FROM BadgeScanLog b WHERE b.success = true AND " +
			"(:fromDate IS NULL OR b.timestamp >= :fromDate) AND " +
			"(:toDate IS NULL OR b.timestamp <= :toDate)")
	Long countSuccessfulScansInPeriod(@Param("fromDate") LocalDateTime fromDate, @Param("toDate") LocalDateTime toDate);
}

