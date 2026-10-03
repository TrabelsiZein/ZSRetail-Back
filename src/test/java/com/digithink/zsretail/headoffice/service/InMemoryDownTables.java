package com.digithink.zsretail.headoffice.service;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.model.HoDownChange;
import com.digithink.zsretail.headoffice.model.HoDownSequence;
import com.digithink.zsretail.headoffice.repository.HoDownChangeRepository;
import com.digithink.zsretail.headoffice.repository.HoDownSequenceRepository;
import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * Test support (task 3.3): ho_down_change and ho_down_sequence in memory, applying the rules of the JPQL queries, and a
 * real {@link CopiesDownFeed} over them.
 */
final class InMemoryDownTables {

	final List<HoDownChange> changes = new ArrayList<>();
	final Map<DataDomain, Long> sequences = new EnumMap<>(DataDomain.class);

	CopiesDownFeed feed(List<DownDomainProvider> providers) {
		return new CopiesDownFeed(changeRepository(), sequenceRepository(), providers,
				TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction());
	}

	HoDownChangeRepository changeRepository() {
		return proxy(HoDownChangeRepository.class, (method, args) -> {
			switch (method) {
				case "findByDomainAndRecordCodeAndStoreId":
					return changes.stream().filter(c -> c.getDomain() == args[0] && c.getRecordCode().equals(args[1])
							&& Objects.equals(c.getStoreId(), args[2])).findFirst();
				case "findByDomainAndRecordCodeAndStoreIdIsNull":
					return changes.stream().filter(c -> c.getDomain() == args[0] && c.getRecordCode().equals(args[1])
							&& c.getStoreId() == null).findFirst();
				case "save":
					HoDownChange change = (HoDownChange) args[0];
					if (changes.stream().noneMatch(c -> c == change)) {
						changes.add(change);
					}
					return change;
				case "findChanged": {
					long storeId = (Long) args[1];
					Map<String, Long> max = new LinkedHashMap<>();
					for (HoDownChange c : changes) {
						if (c.getDomain() == args[0] && (c.getStoreId() == null || c.getStoreId() == storeId)
								&& c.getChangeVersion() > (Long) args[2] && c.getChangeVersion() <= (Long) args[3]) {
							max.merge(c.getRecordCode(), c.getChangeVersion(), Math::max);
						}
					}
					return max.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
							.limit(((Pageable) args[4]).getPageSize())
							.map(e -> new Object[] { e.getKey(), e.getValue() }).collect(Collectors.toList());
				}
				case "findCodes":
					return changes.stream().filter(c -> c.getDomain() == args[0]).map(HoDownChange::getRecordCode)
							.distinct().collect(Collectors.toList());
				default:
					throw new UnsupportedOperationException(method);
			}
		});
	}

	HoDownSequenceRepository sequenceRepository() {
		return proxy(HoDownSequenceRepository.class, (method, args) -> {
			switch (method) {
				case "increment":
					if (!sequences.containsKey(args[0])) {
						return 0;
					}
					sequences.merge((DataDomain) args[0], 1L, Long::sum);
					return 1;
				case "lastVersion":
					Long value = sequences.get(args[0]);
					return value == null ? new ArrayList<>() : new ArrayList<>(Collections.singletonList(value));
				case "save":
					HoDownSequence sequence = (HoDownSequence) args[0];
					sequences.put(sequence.getDomain(), sequence.getLastVersion());
					return sequence;
				default:
					throw new UnsupportedOperationException(method);
			}
		});
	}

	interface Handler {
		Object handle(String method, Object[] args);
	}

	@SuppressWarnings("unchecked")
	static <T> T proxy(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "Stub";
				default: return handler.handle(method.getName(), args == null ? new Object[0] : args);
			}
		});
	}
}
