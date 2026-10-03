package com.digithink.zsretail.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.holink.repository.LinkExchangeRepository;

/**
 * Head office plan, task 2.6: the exchange log. A row per call; a write failure never throws; rows older than the
 * retention are purged, at most once a day; the log page filters by job, result and dates, newest first, paged. The
 * repository stub applies the rules of the JPQL query (checked at L2). No Spring context.
 */
class LinkExchangeLogTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 12, 0);

	private final List<LinkExchange> table = new ArrayList<>();
	private final List<LocalDateTime> purgedBefore = new ArrayList<>();
	private boolean databaseDown;
	private LinkExchangeLog log;

	@BeforeEach
	void setUp() {
		LinkExchangeRepository repository = (LinkExchangeRepository) Proxy.newProxyInstance(
				LinkExchangeRepository.class.getClassLoader(), new Class<?>[] { LinkExchangeRepository.class },
				(proxy, method, args) -> {
					if (databaseDown) {
						throw new IllegalStateException("database down");
					}
					switch (method.getName()) {
						case "save":
							LinkExchange row = (LinkExchange) args[0];
							row.setId((long) table.size() + 1);
							table.add(row);
							return row;
						case "deleteOlderThan":
							purgedBefore.add((LocalDateTime) args[0]);
							int before = table.size();
							table.removeIf(r -> r.getExchangeDate().isBefore((LocalDateTime) args[0]));
							return before - table.size();
						case "search":
							Pageable page = (Pageable) args[4];
							List<LinkExchange> rows = table.stream()
									.filter(r -> args[0].equals("") || args[0].equals(r.getJob()))
									.filter(r -> ((Collection<?>) args[1]).contains(r.getResult()))
									.filter(r -> !r.getExchangeDate().isBefore((LocalDateTime) args[2])
											&& !r.getExchangeDate().isAfter((LocalDateTime) args[3]))
									.sorted(Comparator.comparing(LinkExchange::getExchangeDate).reversed())
									.collect(Collectors.toList());
							int from = (int) Math.min(page.getOffset(), rows.size());
							return new PageImpl<>(rows.subList(from, Math.min(from + page.getPageSize(), rows.size())),
									page, rows.size());
						default:
							throw new UnsupportedOperationException(method.getName());
					}
				});
		log = new LinkExchangeLog(repository, TransactionOperations.withoutTransaction(), 30);
	}

	private void row(String job, LinkJobResult result, LocalDateTime at) {
		log.record(job, ExchangeDirection.UP, 3, result, result == LinkJobResult.SUCCESS ? null : "x", at, 120);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> content(Map<String, Object> page) {
		return (List<Map<String, Object>>) page.get("content");
	}

	@Test
	@DisplayName("A row has date, job, direction, records, result, error (cut to 1000) and duration; a write failure is swallowed")
	void record() {
		StringBuilder longError = new StringBuilder();
		for (int i = 0; i < 1200; i++) {
			longError.append('e');
		}
		log.record("SALES_PUSH", ExchangeDirection.UP, 50, LinkJobResult.WARNING, longError.toString(), NOW, 830);

		LinkExchange row = table.get(0);
		assertEquals(NOW, row.getExchangeDate());
		assertEquals("SALES_PUSH", row.getJob());
		assertEquals(ExchangeDirection.UP, row.getDirection());
		assertEquals(50, row.getRecordCount());
		assertEquals(LinkJobResult.WARNING, row.getResult());
		assertEquals(1000, row.getError().length());
		assertEquals(Long.valueOf(830), row.getDurationMs());

		databaseDown = true;
		log.record("SALES_PUSH", ExchangeDirection.UP, 50, LinkJobResult.SUCCESS, null, NOW, 1); // no exception
		assertEquals(1, table.size());
	}

	@Test
	@DisplayName("Purge: rows older than 30 days deleted at the first call, then not again for a day; a failure is swallowed")
	void purge() {
		row("SALES_PUSH", LinkJobResult.SUCCESS, NOW.minusDays(31));
		row("SALES_PUSH", LinkJobResult.SUCCESS, NOW.minusDays(29));

		log.purgeIfDue(NOW);
		assertEquals(Arrays.asList(NOW.minusDays(30)), purgedBefore);
		assertEquals(1, table.size());

		log.purgeIfDue(NOW.plusHours(23));
		assertEquals(1, purgedBefore.size(), "not twice in a day");
		log.purgeIfDue(NOW.plusDays(1));
		assertEquals(2, purgedBefore.size());

		databaseDown = true;
		log.purgeIfDue(NOW.plusDays(2)); // no exception
		assertEquals(30, log.getRetentionDays());
	}

	@Test
	@DisplayName("Log page: filters by job, result (all = any) and dates; newest first; paged; bad values refused")
	void search() {
		row("HEARTBEAT", LinkJobResult.ERROR, NOW.minusHours(5));
		row("SALES_PUSH", LinkJobResult.SUCCESS, NOW.minusHours(4));
		row("SALES_PUSH", LinkJobResult.WARNING, NOW.minusHours(3));
		row("SALES_PUSH", LinkJobResult.ERROR, NOW.minusDays(2));

		Map<String, Object> all = log.search(null, null, null, null, null, null);
		assertEquals(4L, all.get("totalElements"));
		assertEquals(20, all.get("size"));
		assertEquals(Arrays.asList("id", "exchangeDate", "job", "direction", "recordCount", "result", "error", "durationMs"),
				new ArrayList<>(content(all).get(0).keySet()));
		assertEquals(NOW.minusHours(3), content(all).get(0).get("exchangeDate"), "newest first");

		assertEquals(3L, log.search(" sales_push ", "all", null, null, null, null).get("totalElements"));
		assertEquals(2L, log.search(null, "error", null, null, null, null).get("totalElements"));
		assertEquals(3L, log.search(null, null, "2026-10-03", "2026-10-03", null, null).get("totalElements"));
		assertEquals(1L, log.search(null, null, "2026-10-03T08:30", null, null, null).get("totalElements"));

		Map<String, Object> second = log.search(null, null, null, null, 1, 3);
		assertEquals(1, content(second).size());
		assertEquals(2, second.get("totalPages"));

		IllegalArgumentException result = assertThrows(IllegalArgumentException.class,
				() -> log.search(null, "FAILED", null, null, null, null));
		assertTrue(result.getMessage().startsWith("Invalid result 'FAILED'"));
		assertThrows(IllegalArgumentException.class, () -> log.search(null, null, "03/10/2026", null, null, null));
	}
}
