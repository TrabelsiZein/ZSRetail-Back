package com.digithink.zsretail.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.holink.dto.DownRecordRowDTO;
import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.holink.repository.DownRecordRepository;
import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * Head office plan, task 3.5: the tracking rows of the records received from the head office. A row is written only
 * when something changed; the reception time moves only with a new copy, the status time only with a new status; a
 * retry keeps the stored copy; texts are cut to 1000 characters; the rows to retry; counts with every status.
 */
class DownRecordLogTest {

	private static final DataDomain D = DataDomain.PROMOTIONS;

	private final Map<String, DownRecord> table = new LinkedHashMap<>();
	private int writes;
	private LocalDateTime now = LocalDateTime.of(2026, 10, 3, 9, 0);

	private final DownRecordLog log = new DownRecordLog(repository(), () -> now);

	@Test
	@DisplayName("Written only when something changed; reception and status times move only with their own change")
	void track() {
		assertTrue(log.track(D, "P1", "Summer", DownRecordStatus.WAITING, "not in this store: item I9", null, "{\"v\":1}"));
		DownRecord row = table.get("P1");
		assertEquals(LocalDateTime.of(2026, 10, 3, 9, 0), row.getReceivedAt());
		assertEquals(row.getReceivedAt(), row.getStatusSince());

		now = now.plusMinutes(1);
		assertFalse(log.track(D, "P1", "Summer", DownRecordStatus.WAITING, "not in this store: item I9", null, "{\"v\":1}"));
		assertFalse(log.track(D, "P1", null, DownRecordStatus.WAITING, "not in this store: item I9", null, null),
				"a retry with the same outcome");
		assertEquals(1, writes);

		assertTrue(log.track(D, "P1", null, DownRecordStatus.APPLIED, null, null, null));
		assertEquals(DownRecordStatus.APPLIED, row.getStatus());
		assertEquals(LocalDateTime.of(2026, 10, 3, 9, 1), row.getStatusSince());
		assertEquals(LocalDateTime.of(2026, 10, 3, 9, 0), row.getReceivedAt(), "same copy: reception unchanged");
		assertEquals("{\"v\":1}", row.getPayload(), "a retry keeps the copy");
		assertEquals("Summer", row.getRecordName());
		assertNull(row.getReason());

		now = now.plusMinutes(1);
		assertTrue(log.track(D, "P1", "Summer", DownRecordStatus.APPLIED, null, null, "{\"v\":2}"));
		assertEquals(LocalDateTime.of(2026, 10, 3, 9, 2), row.getReceivedAt());
		assertEquals(LocalDateTime.of(2026, 10, 3, 9, 1), row.getStatusSince(), "same status");

		StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 1200; i++) {
			longText.append('x');
		}
		log.track(D, "P2", "Long", DownRecordStatus.ERROR, longText.toString(), longText.toString(), "{}");
		assertEquals(1000, table.get("P2").getReason().length());
		assertEquals(1000, table.get("P2").getInfo().length());
	}

	@Test
	@DisplayName("Rows to retry (WAITING and ERROR, by code), counts with every status, removal, bad status refused")
	void retryCountsRemove() {
		log.track(D, "C", "c", DownRecordStatus.ERROR, "clash", null, "{}");
		log.track(D, "B", "b", DownRecordStatus.APPLIED, null, null, "{}");
		log.track(D, "A", "a", DownRecordStatus.WAITING, "missing", null, "{}");
		assertEquals("A,C", log.toRetry(D).stream().map(DownRecord::getRecordCode).collect(Collectors.joining(",")));
		Map<String, Long> counts = log.counts(D);
		assertEquals("{APPLIED=1, WAITING=1, ERROR=1}", counts.toString());
		assertTrue(log.remove(D, "C"));
		assertFalse(log.remove(D, "C"));
		assertEquals(0L, log.counts(D).get("ERROR"));
		assertThrows(IllegalArgumentException.class, () -> log.list(D, "LATE", null, null));
		assertEquals(1, ((List<?>) log.list(D, " waiting ", null, null).get("records")).size());
		assertEquals(1, ((List<?>) log.list(D, "all", null, null).get("records")).size(), "the applied B is not listed");
	}

	@Test
	@DisplayName("The list: only WAITING and ERROR, ERROR first then by code, paged by the repository; APPLIED counted, never listed")
	void listToCheck() {
		for (String code : new String[] { "W2", "A1", "E2", "W1", "A2", "E1", "W3" }) {
			DownRecordStatus status = code.startsWith("E") ? DownRecordStatus.ERROR
					: code.startsWith("W") ? DownRecordStatus.WAITING : DownRecordStatus.APPLIED;
			log.track(D, code, "n" + code, status, status == DownRecordStatus.APPLIED ? null : "why " + code, null,
					"{\"big\":1}");
		}
		Map<String, Object> all = log.list(D, null, null, null);
		assertEquals("E1,E2,W1,W2,W3", codes(all));
		assertEquals(5L, all.get("totalElements"));
		assertEquals(0, all.get("page"));
		assertEquals(20, all.get("size"));
		assertEquals("{APPLIED=2, WAITING=3, ERROR=2}", all.get("counts").toString());
		assertEquals("[domain, counts, records, totalElements, page, size]", all.keySet().toString());
		Map<?, ?> first = (Map<?, ?>) ((List<?>) all.get("records")).get(0);
		assertEquals("[code, name, status, reason, info, receivedAt, statusSince]", first.keySet().toString());
		assertEquals("why E1", first.get("reason"));
		assertFalse(first.containsKey("payload"));

		Map<String, Object> second = log.list(D, "", 1, 2);
		assertEquals("W1,W2", codes(second));
		assertEquals(5L, second.get("totalElements"));
		assertEquals(1, second.get("page"));
		assertEquals(2, second.get("size"));
		assertEquals("E1,E2", codes(log.list(D, "Error", null, null)));
		assertEquals("W1,W2,W3", codes(log.list(D, "WAITING", -1, 0)), "page below 0 and size below 1: the defaults");
		assertEquals(200, log.list(D, null, 0, 5000).get("size"), "at most 200");
		IllegalArgumentException applied = assertThrows(IllegalArgumentException.class,
				() -> log.list(D, "APPLIED", null, null));
		assertTrue(applied.getMessage().contains("[WAITING, ERROR]"), applied.getMessage());
	}

	private static String codes(Map<String, Object> answer) {
		return ((List<?>) answer.get("records")).stream().map(r -> (String) ((Map<?, ?>) r).get("code"))
				.collect(Collectors.joining(","));
	}

	private DownRecordRepository repository() {
		return (DownRecordRepository) Proxy.newProxyInstance(DownRecordRepository.class.getClassLoader(),
				new Class<?>[] { DownRecordRepository.class }, (proxy, method, args) -> {
					switch (method.getName()) {
						case "findByDomainAndRecordCode":
							return Optional.ofNullable(table.get(args[1]));
						case "save":
							DownRecord row = (DownRecord) args[0];
							table.put(row.getRecordCode(), row);
							writes++;
							return row;
						case "delete":
							table.remove(((DownRecord) args[0]).getRecordCode());
							return null;
						case "findByDomainAndStatusIn":
							Collection<?> statuses = (Collection<?>) args[1];
							return table.values().stream().filter(r -> statuses.contains(r.getStatus()))
									.collect(Collectors.toList());
						case "findToCheck": {
							// As the query: the asked statuses, by status name then code, one page, no payload
							Collection<?> asked = (Collection<?>) args[1];
							Pageable page = (Pageable) args[2];
							List<DownRecordRowDTO> matching = table.values().stream().filter(r -> asked.contains(r.getStatus()))
									.sorted(Comparator.comparing((DownRecord r) -> r.getStatus().name())
											.thenComparing(DownRecord::getRecordCode))
									.map(r -> new DownRecordRowDTO(r.getRecordCode(), r.getRecordName(), r.getStatus(), r.getReason(),
											r.getInfo(), r.getReceivedAt(), r.getStatusSince()))
									.collect(Collectors.toList());
							int from = (int) Math.min(page.getOffset(), matching.size());
							int to = Math.min(from + page.getPageSize(), matching.size());
							return new PageImpl<>(matching.subList(from, to), page, matching.size());
						}
						case "countByStatus":
							List<Object[]> rows = new ArrayList<>();
							table.values().stream().collect(Collectors.groupingBy(DownRecord::getStatus,
									Collectors.counting())).forEach((s, n) -> rows.add(new Object[] { s, n }));
							return rows;
						default:
							throw new UnsupportedOperationException(method.getName());
					}
				});
	}
}
