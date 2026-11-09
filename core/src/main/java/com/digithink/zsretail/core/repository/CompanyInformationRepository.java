package com.digithink.zsretail.core.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.model.CompanyInformation;

@Repository
public interface CompanyInformationRepository extends JpaRepository<CompanyInformation, Long> {

	/** The one row: on every database made so far its id is 1, but IDENTITY gives the id, not this code. */
	Optional<CompanyInformation> findFirstByOrderByIdAsc();
}
