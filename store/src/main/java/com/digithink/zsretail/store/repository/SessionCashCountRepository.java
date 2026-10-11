package com.digithink.zsretail.store.repository;

import java.util.List;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.enumeration.CounterType;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.store.model.SessionCashCount;

public interface SessionCashCountRepository extends _BaseRepository<SessionCashCount, Long> {

	List<SessionCashCount> findByCashierSession(CashierSession cashierSession);

	List<SessionCashCount> findByCashierSessionAndCounterType(CashierSession cashierSession, CounterType counterType);
}

