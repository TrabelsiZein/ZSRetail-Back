package com.digithink.zsretail.core.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import com.digithink.zsretail.core.model.Customer;

public interface CustomerRepository extends _BaseRepository<Customer, Long> {

	Optional<Customer> findByCustomerCode(String customerCode);
	
	long countByCreatedAtGreaterThanEqual(LocalDateTime date);

	Optional<Customer> findByErpExternalId(String erpExternalId);

	Optional<Customer> findByIsDefaultTrue();

}

