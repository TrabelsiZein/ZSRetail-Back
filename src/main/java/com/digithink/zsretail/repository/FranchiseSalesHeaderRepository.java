package com.digithink.zsretail.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.model.FranchiseSalesHeader;

/**
 * Repository for franchise client sales data received by the admin.
 * Used for tracking and dashboards only.
 */
public interface FranchiseSalesHeaderRepository extends _BaseRepository<FranchiseSalesHeader, Long> {

	List<FranchiseSalesHeader> findByLocationCode(String locationCode);

	Page<FranchiseSalesHeader> findByLocationCode(String locationCode, Pageable pageable);

	boolean existsByLocationCodeAndExternalSalesNumber(String locationCode, String externalSalesNumber);
}
