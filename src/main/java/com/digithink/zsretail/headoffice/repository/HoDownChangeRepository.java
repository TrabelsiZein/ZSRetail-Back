package com.digithink.zsretail.headoffice.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoDownChange;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoDownChangeRepository extends _BaseRepository<HoDownChange, Long> {

	Optional<HoDownChange> findByDomainAndRecordCodeAndStoreId(DataDomain domain, String recordCode, Long storeId);

	Optional<HoDownChange> findByDomainAndRecordCodeAndStoreIdIsNull(DataDomain domain, String recordCode);

	/**
	 * [code, last change number] of the records of a domain changed for this store (its own rows and the every-store
	 * rows) after {@code after} and up to {@code upTo}, oldest change first. The page bounds the number of codes.
	 */
	@Query("select c.recordCode, max(c.changeVersion) from HoDownChange c where c.domain = :domain"
			+ " and (c.storeId = :storeId or c.storeId is null) and c.changeVersion > :after and c.changeVersion <= :upTo"
			+ " group by c.recordCode order by max(c.changeVersion)")
	List<Object[]> findChanged(@Param("domain") DataDomain domain, @Param("storeId") long storeId,
			@Param("after") long after, @Param("upTo") long upTo, Pageable page);

	/** Every code of a domain that has a change row (the startup backfill). */
	@Query("select distinct c.recordCode from HoDownChange c where c.domain = :domain")
	List<String> findCodes(@Param("domain") DataDomain domain);
}
