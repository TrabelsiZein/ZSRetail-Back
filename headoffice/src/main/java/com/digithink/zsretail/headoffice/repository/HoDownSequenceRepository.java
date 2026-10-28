package com.digithink.zsretail.headoffice.repository;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.headoffice.model.HoDownSequence;

/**
 * The change number of each domain (ho_down_sequence). 3.1.1: native, the domain cast to varchar (the driver sends text
 * as nvarchar, the column is varchar): SQL Server seeks uk_ho_down_sequence_domain and touches the domain's own row
 * only. Without the cast it scanned the table, so a store's pull of LOYALTY, PROMOTIONS or SUPPLY waited behind a
 * catalogue writer holding the CATALOGUE row and timed out (HTTP 500 on ZEN's test head office, 2026-10-28).
 */
public interface HoDownSequenceRepository extends _BaseRepository<HoDownSequence, Long> {

	/** The query of {@link #incrementBy}. */
	String INCREMENT_BY = "update ho_down_sequence set last_version = last_version + :count"
			+ " where domain = cast(:domain as varchar(20))";

	/** The query of {@link #lastVersion}. */
	String LAST_VERSION = "select last_version from ho_down_sequence where domain = cast(:domain as varchar(20))";

	@Modifying(flushAutomatically = true)
	@Query(value = INCREMENT_BY, nativeQuery = true)
	int incrementByOfDomain(@Param("domain") String domain, @Param("count") long count);

	@Query(value = LAST_VERSION, nativeQuery = true)
	List<Number> lastVersionOfDomain(@Param("domain") String domain);

	/**
	 * Adds 1 to the domain's change number; the row stays locked until the caller's transaction ends. Returns the number
	 * of rows updated (0 when the domain has no row yet). The pending changes are flushed first; the persistence context
	 * is not cleared.
	 */
	default int increment(DataDomain domain) {
		return incrementByOfDomain(domain.name(), 1);
	}

	/**
	 * Step 6: adds count to the domain's change number in one update (the startup backfill reserves one number per record
	 * of a chunk); the row stays locked until the caller's transaction ends. Returns the number of rows updated.
	 */
	default int incrementBy(DataDomain domain, long count) {
		return incrementByOfDomain(domain.name(), count);
	}

	/** The domain's change number as committed (or as written by this transaction); empty when it has no row. */
	default List<Long> lastVersion(DataDomain domain) {
		return lastVersionOfDomain(domain.name()).stream().map(Number::longValue).collect(Collectors.toList());
	}
}
