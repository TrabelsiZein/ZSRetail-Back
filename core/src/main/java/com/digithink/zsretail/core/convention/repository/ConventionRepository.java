package com.digithink.zsretail.core.convention.repository;

import java.util.Optional;

import javax.persistence.LockModeType;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.convention.model.Convention;
import com.digithink.zsretail.core.repository._BaseRepository;

/**
 * 3.6.0, conventions step 1: the conventions. The list's filters are Specifications (ConventionService: the state of
 * today, the text).
 */
@Repository
public interface ConventionRepository extends _BaseRepository<Convention, Long> {

	/** The code as stored (the service upper-cases it first). */
	Optional<Convention> findByCode(String code);

	/**
	 * Part B4: the convention read under a row lock held until the transaction ends (SQL Server: updlock, holdlock,
	 * rowlock): two generations of one convention take its counter one after the other.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Convention c where c.id = :id")
	Optional<Convention> lockById(@Param("id") Long id);
}
