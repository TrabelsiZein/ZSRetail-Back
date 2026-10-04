package com.digithink.zsretail.headoffice.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoNumberSequence;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoNumberSequenceRepository extends _BaseRepository<HoNumberSequence, Long> {

	/** Adds 1 to the sequence; the row stays locked until the caller's transaction ends. 0 when it has no row yet. */
	@Modifying(flushAutomatically = true)
	@Query("update HoNumberSequence s set s.lastValue = s.lastValue + 1 where s.code = :code")
	int increment(@Param("code") String code);

	/** The sequence's last value (as written by this transaction); empty when it has no row. */
	@Query("select s.lastValue from HoNumberSequence s where s.code = :code")
	List<Long> lastValue(@Param("code") String code);
}
