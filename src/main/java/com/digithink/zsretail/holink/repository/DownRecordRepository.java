package com.digithink.zsretail.holink.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.repository._BaseRepository;

public interface DownRecordRepository extends _BaseRepository<DownRecord, Long> {

	Optional<DownRecord> findByDomainAndRecordCode(DataDomain domain, String recordCode);

	List<DownRecord> findByDomainAndStatusIn(DataDomain domain, Collection<DownRecordStatus> statuses);

	/** [status, count] of a domain's rows. */
	@Query("select r.status, count(r) from DownRecord r where r.domain = :domain group by r.status")
	List<Object[]> countByStatus(@Param("domain") DataDomain domain);
}
