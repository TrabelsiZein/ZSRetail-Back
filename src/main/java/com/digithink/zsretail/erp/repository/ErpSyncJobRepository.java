package com.digithink.zsretail.erp.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.erp.model.ErpSyncJob;
import com.digithink.zsretail.repository._BaseRepository;

@Repository
public interface ErpSyncJobRepository extends _BaseRepository<ErpSyncJob, Long> {

	Optional<ErpSyncJob> findByJobType(ErpSyncJobType jobType);

	List<ErpSyncJob> findByEnabledTrueOrderByJobTypeAsc();

	List<ErpSyncJob> findByJobTypeAndEnabledTrue(ErpSyncJobType jobType);
}
