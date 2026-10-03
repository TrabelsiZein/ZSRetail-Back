package com.digithink.zsretail.holink.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.repository.LinkExchangeRepository;
import com.digithink.zsretail.holink.service.LinkExchangeLog;
import com.digithink.zsretail.holink.service.SalesPushService;
import com.digithink.zsretail.holink.service.SalesPushSettings;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Head office plan, task 2.4, a job since task 2.6: code, first run 20 s after the start (after the first heartbeat),
 * default frequency from the settings; a cycle that throws is logged and the job answers an ERROR run without
 * throwing. The exchange log rows of real cycles are checked in SalesPushServiceTest.
 */
class SalesPushJobTest {

	private ListAppender<ILoggingEvent> logs;
	private int logRows;

	@BeforeEach
	void setUp() {
		logs = new ListAppender<>();
		logs.start();
		logger().addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		logger().detachAppender(logs);
	}

	private static Logger logger() {
		return (Logger) LoggerFactory.getLogger(SalesPushJob.class);
	}

	private List<String> lines(Level level) {
		return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.toList());
	}

	private LinkExchangeLog exchangeLog() {
		LinkExchangeRepository repository = (LinkExchangeRepository) Proxy.newProxyInstance(
				LinkExchangeRepository.class.getClassLoader(), new Class<?>[] { LinkExchangeRepository.class },
				(proxy, method, args) -> {
					logRows++;
					return args == null ? null : args[0];
				});
		return new LinkExchangeLog(repository, TransactionOperations.withoutTransaction(), 30);
	}

	@Test
	@DisplayName("Job: code SALES_PUSH, first run 20 s after the start, default frequency headoffice.sales-push.interval-seconds")
	void job() {
		SalesPushJob job = new SalesPushJob(null, new SalesPushSettings("", 50, 90), exchangeLog());
		assertEquals("SALES_PUSH", job.getCode());
		assertEquals(20, job.getFirstDelay().getSeconds());
		assertEquals(90, job.getDefaultIntervalSeconds());
	}

	@Test
	@DisplayName("A cycle that throws: WARN line, ERROR run with the cause, no exception, no exchange row, no catch-up")
	void neverThrows() {
		SalesPushService failing = new SalesPushService(null, null, null, null, null, null, null, null) {
			@Override
			public Cycle runCycle() {
				throw new IllegalStateException("hol_sales_copy unreadable");
			}
		};
		SalesPushJob job = new SalesPushJob(failing, new SalesPushSettings("", 50, 60), exchangeLog());

		LinkJobRun run = job.run();

		assertEquals(LinkJobResult.ERROR, run.getResult());
		assertEquals("cycle failed (IllegalStateException: hol_sales_copy unreadable)", run.getMessage());
		assertFalse(run.isRunAgainSoon());
		assertEquals(0, logRows);
		assertEquals(1, lines(Level.WARN).size());
		assertTrue(lines(Level.WARN).get(0).startsWith("Head office sales push: cycle failed, retried at the next cycle"));
	}
}
