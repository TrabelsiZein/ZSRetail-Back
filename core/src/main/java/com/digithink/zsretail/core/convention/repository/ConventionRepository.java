package com.digithink.zsretail.core.convention.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.convention.enumeration.ConventionStatus;
import com.digithink.zsretail.core.convention.model.Convention;
import com.digithink.zsretail.core.repository._BaseRepository;

/** 3.6.0, conventions step 1: the conventions. */
@Repository
public interface ConventionRepository extends _BaseRepository<Convention, Long> {

	/** The code as stored (the service upper-cases it first). */
	Optional<Convention> findByCode(String code);

	/**
	 * Part B3, the list: one status or all (null), and a text found in the code, the name or the organisation (null: any;
	 * else "%text%" in lower case, its % _ [ and \ escaped by \). The order is the Pageable's.
	 */
	String SEARCH = "from Convention c where (:status is null or c.status = :status) and (:q is null"
			+ " or lower(c.code) like :q escape '\\' or lower(c.name) like :q escape '\\'"
			+ " or lower(c.organisation) like :q escape '\\')";

	@Query(value = "select c " + SEARCH, countQuery = "select count(c) " + SEARCH)
	Page<Convention> search(@Param("status") ConventionStatus status, @Param("q") String q, Pageable pageable);
}
