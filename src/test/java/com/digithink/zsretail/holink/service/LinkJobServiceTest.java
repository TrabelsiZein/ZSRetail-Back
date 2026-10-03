package com.digithink.zsretail.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.model.LinkJobState;
import com.digithink.zsretail.holink.repository.LinkJobStateRepository;
import com.digithink.zsretail.holink.scheduler.LinkJob;

/**
 * Head office plan, task 2.6: the jobs' saved frequency and last run. The default while nothing is saved; a saved value
 * read back at start; limits 10 s to 24 h; a database failure never stops a job (defaults used, retried; a run is
 * remembered in memory even when it cannot be written). In-memory hol_job, no Spring context.
 */
class LinkJobServiceTest {

	private final Map<String, LinkJobState> table = new LinkedHashMap<>();
	private boolean databaseDown;
	private int reads;

	private final LinkJob push = new LinkJob() {
		@Override
		public String getCode() {
			return "SALES_PUSH";
		}

		@Override
		public long getDefaultIntervalSeconds() {
			return 60;
		}

		@Override
		public Duration getFirstDelay() {
			return Duration.ofSeconds(20);
		}

		@Override
		public LinkJobRun run() {
			return LinkJobRun.success("ok");
		}
	};

	private LinkJobService service;

	@BeforeEach
	void setUp() {
		LinkJobStateRepository repository = (LinkJobStateRepository) Proxy.newProxyInstance(
				LinkJobStateRepository.class.getClassLoader(), new Class<?>[] { LinkJobStateRepository.class },
				(proxy, method, args) -> {
					if (databaseDown) {
						throw new IllegalStateException("database down");
					}
					switch (method.getName()) {
						case "findAll":
							reads++;
							return new ArrayList<>(table.values());
						case "findByCode":
							return Optional.ofNullable(table.get(args[0]));
						case "save":
							LinkJobState row = (LinkJobState) args[0];
							table.put(row.getCode(), row);
							return row;
						default:
							throw new UnsupportedOperationException(method.getName());
					}
				});
		service = new LinkJobService(repository, TransactionOperations.withoutTransaction());
	}

	@Test
	@DisplayName("Default while nothing is saved; a saved frequency (also one saved before the start) is used; null resets")
	void interval() {
		assertEquals(60, service.intervalSeconds(push));

		LinkJobState saved = new LinkJobState();
		saved.setCode("SALES_PUSH");
		saved.setIntervalSeconds(900L);
		table.put("SALES_PUSH", saved);
		LinkJobService restarted = new LinkJobService(
				(LinkJobStateRepository) Proxy.newProxyInstance(LinkJobStateRepository.class.getClassLoader(),
						new Class<?>[] { LinkJobStateRepository.class },
						(proxy, method, args) -> new ArrayList<>(table.values())),
				TransactionOperations.withoutTransaction());
		assertEquals(900, restarted.intervalSeconds(push), "read back at start");

		service.setInterval(push, 30L);
		assertEquals(30, service.intervalSeconds(push));
		service.setInterval(push, null);
		assertEquals(60, service.intervalSeconds(push));
		assertNull(table.get("SALES_PUSH").getIntervalSeconds());
	}

	@Test
	@DisplayName("Limits: 10 s to 86,400 s; outside them nothing is saved")
	void limits() {
		service.setInterval(push, 10L);
		service.setInterval(push, 86_400L);
		for (long bad : new long[] { 9, 0, -5, 86_401 }) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.setInterval(push, bad));
			assertEquals("intervalSeconds must be from 10 to 86400 seconds", e.getMessage());
		}
		assertEquals(86_400, service.intervalSeconds(push));
	}

	@Test
	@DisplayName("Database down: defaults used and the read retried later; a run is remembered in memory without throwing")
	void databaseDown() {
		databaseDown = true;
		assertEquals(60, service.intervalSeconds(push));
		service.recordRun("SALES_PUSH", LinkJobRun.error("not delivered"), LocalDateTime.of(2026, 10, 3, 12, 0), 42);
		assertEquals(LinkJobResult.ERROR, service.state("SALES_PUSH").getLastResult());
		assertEquals(Long.valueOf(42), service.state("SALES_PUSH").getLastDurationMs());
		assertThrows(IllegalStateException.class, () -> service.setInterval(push, 30L), "a change not saved is refused");

		databaseDown = false;
		service.recordRun("SALES_PUSH", LinkJobRun.success("12 sent"), LocalDateTime.of(2026, 10, 3, 12, 1), 7);
		assertEquals(1, reads, "the table is read once it can be");
		assertEquals("12 sent", table.get("SALES_PUSH").getLastMessage());
		assertEquals(LocalDateTime.of(2026, 10, 3, 12, 1), table.get("SALES_PUSH").getLastRunAt());
	}
}
