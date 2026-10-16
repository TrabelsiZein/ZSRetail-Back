package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.headoffice.model.HoDownChange;

public interface HoDownChangeRepository extends _BaseRepository<HoDownChange, Long> {

	Optional<HoDownChange> findByDomainAndRecordCodeAndStoreId(DataDomain domain, String recordCode, Long storeId);

	Optional<HoDownChange> findByDomainAndRecordCodeAndStoreIdIsNull(DataDomain domain, String recordCode);

	/** Stock points, step 3a: the rows of one store for these codes (a bulk recording). */
	List<HoDownChange> findByDomainAndStoreIdAndRecordCodeIn(DataDomain domain, Long storeId,
			java.util.Collection<String> recordCodes);

	/**
	 * The query of {@link #findChanged}, native: the driver sends the domain as nvarchar, the column is varchar; cast, SQL
	 * Server seeks ix_ho_down_change_pull from the cursor and reads the index alone (without the cast it read the whole
	 * table at every page: 552 reads for 43,843 rows, ZEN's test head office).
	 */
	String CHANGED = "select c.record_code, max(c.change_version) from ho_down_change c"
			+ " where c.domain = cast(:domain as varchar(20)) and (c.store_id = :storeId or c.store_id is null)"
			+ " and c.change_version > :after and c.change_version <= :upTo"
			+ " group by c.record_code order by max(c.change_version)";

	@Query(value = CHANGED, nativeQuery = true)
	List<Object[]> findChangedOfDomain(@Param("domain") String domain, @Param("storeId") long storeId,
			@Param("after") long after, @Param("upTo") long upTo, Pageable page);

	/**
	 * [code, last change number] of the records of a domain changed for this store (its own rows and the every-store
	 * rows) after {@code after} and up to {@code upTo}, oldest change first. The page bounds the number of codes.
	 */
	default List<Object[]> findChanged(DataDomain domain, long storeId, long after, long upTo, Pageable page) {
		return findChangedOfDomain(domain.name(), storeId, after, upTo, page);
	}

	/** Every code of a domain that has a change row (the startup backfill). */
	@Query("select distinct c.recordCode from HoDownChange c where c.domain = :domain")
	List<String> findCodes(@Param("domain") DataDomain domain);
}
