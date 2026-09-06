package com.digithink.zsretail.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.model.LoyaltyProgram;

public interface LoyaltyProgramRepository extends _BaseRepository<LoyaltyProgram, Long> {

	/** Legacy: returns the one open-ended program (endDate null and active=true). Kept for backward compat. */
	Optional<LoyaltyProgram> findByActiveTrueAndEndDateIsNull();

	/** All programs flagged active=true, regardless of dates. Used when creating a new one to close previous. */
	List<LoyaltyProgram> findByActiveTrue();

	/** Date-aware: the program currently applicable on the given date (active, started, not yet ended). */
	@Query("SELECT p FROM LoyaltyProgram p WHERE p.active = true "
			+ "AND p.startDate <= :today "
			+ "AND (p.endDate IS NULL OR p.endDate >= :today) "
			+ "ORDER BY p.startDate DESC")
	List<LoyaltyProgram> findCurrentActivePrograms(@Param("today") LocalDate today);

	/** Returns all programs ordered by most recent start date first */
	List<LoyaltyProgram> findAllByOrderByStartDateDesc();

	Optional<LoyaltyProgram> findByProgramCode(String programCode);
}
