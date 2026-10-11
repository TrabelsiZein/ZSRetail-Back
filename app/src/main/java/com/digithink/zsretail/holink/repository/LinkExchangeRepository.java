package com.digithink.zsretail.holink.repository;

import java.time.LocalDateTime;
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.repository._BaseRepository;

public interface LinkExchangeRepository extends _BaseRepository<LinkExchange, Long> {

	/**
	 * Task 2.6: the exchange log. No parameter is null (SQL Server cannot type a null date): job "" = every job,
	 * results = every value when not filtered, the dates are always given.
	 */
	@Query("select e from LinkExchange e where (:job = '' or e.job = :job) and e.result in :results"
			+ " and e.exchangeDate >= :dateFrom and e.exchangeDate <= :dateTo")
	Page<LinkExchange> search(@Param("job") String job, @Param("results") Collection<LinkJobResult> results,
			@Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo, Pageable page);

	/** Task 2.6: purge; returns the number of rows deleted. */
	@Modifying
	@Query("delete from LinkExchange e where e.exchangeDate < :before")
	int deleteOlderThan(@Param("before") LocalDateTime before);
}
