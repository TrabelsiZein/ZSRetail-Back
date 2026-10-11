package com.digithink.zsretail.core.erp.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.core.erp.model.ErpSyncJob;
import com.digithink.zsretail.core.repository._BaseRepository;

@Repository
public interface ErpSyncJobRepository extends _BaseRepository<ErpSyncJob, Long> {

	Optional<ErpSyncJob> findByJobType(ErpSyncJobType jobType);

	List<ErpSyncJob> findByEnabledTrueOrderByJobTypeAsc();

	List<ErpSyncJob> findByJobTypeAndEnabledTrue(ErpSyncJobType jobType);
}
