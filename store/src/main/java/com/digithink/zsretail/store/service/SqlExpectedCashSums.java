package com.digithink.zsretail.store.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.store.repository.ExpectedCashRepository;

/** 3.2.0: {@link ExpectedCashSums} in SQL: three grouped queries for any number of sessions (by chunks of ids). */
@Service
public class SqlExpectedCashSums implements ExpectedCashSums {

	@Autowired
	private ExpectedCashRepository repository;

	@Override
	public Map<Long, Sums> of(Collection<CashierSession> sessions) {
		Map<Long, Sums> out = new LinkedHashMap<>();
		sessions.forEach(s -> out.put(s.getId(), new Sums()));
		List<Long> ids = sessions.stream().map(CashierSession::getId).collect(Collectors.toList());
		for (List<Long> chunk : SessionFiguresService.chunks(ids)) {
			for (Object[] row : repository.cashPaid(chunk, SessionFiguresService.NOT_COUNTED,
					PaymentMethodType.CLIENT_ESPECES)) {
				out.get(id(row[0])).cashPaid = amount(row[1]);
			}
			for (Object[] row : repository.changeGiven(chunk, SessionFiguresService.NOT_COUNTED,
					PaymentMethodType.CLIENT_ESPECES)) {
				out.get(id(row[0])).changeGiven = amount(row[1]);
			}
			for (Object[] row : repository.simpleReturns(chunk, ReturnType.SIMPLE_RETURN)) {
				out.get(id(row[0])).simpleReturns = amount(row[1]);
			}
		}
		return out;
	}

	private static Long id(Object value) {
		return ((Number) value).longValue();
	}

	private static double amount(Object value) {
		return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
	}
}
