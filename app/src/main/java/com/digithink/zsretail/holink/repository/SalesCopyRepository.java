package com.digithink.zsretail.holink.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.holink.model.SalesCopy;
import com.digithink.zsretail.repository._BaseRepository;

public interface SalesCopyRepository extends _BaseRepository<SalesCopy, Long> {

	List<SalesCopy> findByDocumentTypeAndLocalIdIn(SalesCopyType documentType, Collection<Long> localIds);

	/**
	 * Task 2.4: the next copies to send of one type: never tried first (fewest attempts), then oldest document first.
	 */
	@Query("select c from SalesCopy c where c.documentType = :type and c.status in :statuses"
			+ " order by c.attempts, c.documentDate, c.id")
	List<SalesCopy> findQueue(@Param("type") SalesCopyType type, @Param("statuses") Collection<SalesCopyStatus> statuses,
			Pageable page);

	/** Task 2.4: [status, count] per status, for the Head office link page. */
	@Query("select c.status, count(c) from SalesCopy c group by c.status")
	List<Object[]> countByStatus();
}
