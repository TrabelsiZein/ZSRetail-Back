package com.digithink.zsretail.headoffice.repository;

import java.util.List;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoDownSequence;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoDownSequenceRepository extends _BaseRepository<HoDownSequence, Long> {

	/**
	 * Adds 1 to the domain's change number; the row stays locked until the caller's transaction ends. Returns the number
	 * of rows updated (0 when the domain has no row yet). The pending changes are flushed first; the persistence context
	 * is not cleared.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update HoDownSequence s set s.lastVersion = s.lastVersion + 1 where s.domain = :domain")
	int increment(@Param("domain") DataDomain domain);

	/** The domain's change number as committed (or as written by this transaction); empty when it has no row. */
	@Query("select s.lastVersion from HoDownSequence s where s.domain = :domain")
	List<Long> lastVersion(@Param("domain") DataDomain domain);
}
