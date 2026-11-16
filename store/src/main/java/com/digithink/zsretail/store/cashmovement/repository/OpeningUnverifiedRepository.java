package com.digithink.zsretail.store.cashmovement.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.digithink.zsretail.store.cashmovement.model.OpeningUnverified;

/** 3.2.0, the differences, step A: the sessions opened while the previous one of their drawer was not verified. */
public interface OpeningUnverifiedRepository extends JpaRepository<OpeningUnverified, Long> {

	/** Step B: the rows of these sessions (the page "Écarts de caisse"). */
	List<OpeningUnverified> findBySessionIdIn(Collection<Long> sessionIds);
}
