package com.digithink.zsretail.repository;

import java.util.List;

import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.SessionCashCount;
import com.digithink.zsretail.model.enumeration.CounterType;

public interface SessionCashCountRepository extends _BaseRepository<SessionCashCount, Long> {

	List<SessionCashCount> findByCashierSession(CashierSession cashierSession);

	List<SessionCashCount> findByCashierSessionAndCounterType(CashierSession cashierSession, CounterType counterType);
}

