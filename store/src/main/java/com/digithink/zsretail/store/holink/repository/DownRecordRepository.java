package com.digithink.zsretail.store.holink.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.holink.dto.DownRecordRowDTO;
import com.digithink.zsretail.store.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.store.holink.model.DownRecord;

public interface DownRecordRepository extends _BaseRepository<DownRecord, Long> {

	Optional<DownRecord> findByDomainAndRecordCode(DataDomain domain, String recordCode);

	/** The rows of these codes, one query for a page of copies down (DownRecordLog.rowsOf). */
	List<DownRecord> findByDomainAndRecordCodeIn(DataDomain domain, Collection<String> recordCodes);

	List<DownRecord> findByDomainAndStatusIn(DataDomain domain, Collection<DownRecordStatus> statuses);

	/** The rows to check (DownRecordLog.list): every column but the payload, sorted and paged by the database. */
	String TO_CHECK = "select new com.digithink.zsretail.store.holink.dto.DownRecordRowDTO(r.recordCode, r.recordName, r.status,"
			+ " r.reason, r.info, r.receivedAt, r.statusSince) from DownRecord r"
			+ " where r.domain = :domain and r.status in :statuses order by r.status, r.recordCode";

	String TO_CHECK_COUNT = "select count(r) from DownRecord r where r.domain = :domain and r.status in :statuses";

	/**
	 * A page of a domain's rows of these statuses, by status then code. The status is stored as its name: with only
	 * ERROR and WAITING asked (APPLIED is never listed), ERROR comes first.
	 */
	@Query(value = TO_CHECK, countQuery = TO_CHECK_COUNT)
	Page<DownRecordRowDTO> findToCheck(@Param("domain") DataDomain domain,
			@Param("statuses") Collection<DownRecordStatus> statuses, Pageable page);

	/** [status, count] of a domain's rows. */
	@Query("select r.status, count(r) from DownRecord r where r.domain = :domain group by r.status")
	List<Object[]> countByStatus(@Param("domain") DataDomain domain);
}
