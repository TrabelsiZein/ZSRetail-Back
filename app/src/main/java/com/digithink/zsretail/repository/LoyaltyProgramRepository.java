package com.digithink.zsretail.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.enumeration.RecordOrigin;

public interface LoyaltyProgramRepository extends _BaseRepository<LoyaltyProgram, Long> {

	/** Step 4: active programs not from this origin (null counts as LOCAL), set inactive at the pull. */
	@Query("select p from LoyaltyProgram p where p.active = true and (p.origin is null or p.origin <> :origin)")
	List<LoyaltyProgram> findActiveNotFrom(@Param("origin") RecordOrigin origin);

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
