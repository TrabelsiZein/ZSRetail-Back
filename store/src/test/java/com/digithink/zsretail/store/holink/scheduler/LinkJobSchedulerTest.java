package com.digithink.zsretail.store.holink.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TriggerContext;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.store.holink.dto.LinkJobDTO;
import com.digithink.zsretail.store.holink.dto.LinkJobRun;
import com.digithink.zsretail.store.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.store.holink.model.LinkJobState;
import com.digithink.zsretail.store.holink.repository.LinkExchangeRepository;
import com.digithink.zsretail.store.holink.repository.LinkJobStateRepository;
import com.digithink.zsretail.store.holink.service.LinkExchangeLog;
import com.digithink.zsretail.store.holink.service.LinkJobService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Head office plan, task 2.6: the jobs of the head office link run on the single ho-link-1 thread. First run after the
 * job's first delay, then its frequency in force (a frequency change is used by the next run), or the catch-up delay
 * while it has more to do; each run recorded; a job that throws is recorded as ERROR and the scheduler goes on; run now
 * on ho-link-1; reschedule after a change; the exchange log purged when due. In-memory hol_job and log, no Spring
 * context.
 */
class LinkJobSchedulerTest {

	/** hol_job, by code. */
	private final Map<String, LinkJobState> jobTable = new LinkedHashMap<>();
	private int purges;
	private LinkJobService jobService;
	private LinkExchangeLog exchangeLog;
	private LinkJobScheduler scheduler;
	private ListAppender<ILoggingEvent> logs;

	private final FakeJob heartbeat = new FakeJob("HEARTBEAT", 60, 15);
	private final FakeJob push = new FakeJob("SALES_PUSH", 120, 20);

	/** A job whose run is chosen by the test; it remembers the thread it ran on. */
	private static final class FakeJob implements LinkJob {
		final String code;
		final long interval;
		final long firstDelay;
		LinkJobRun next = LinkJobRun.success("ok");
		RuntimeException failure;
		volatile String thread;
		int runs;

		FakeJob(String code, long interval, long firstDelay) {
			this.code = code;
			this.interval = interval;
			this.firstDelay = firstDelay;
		}

		@Override
		public String getCode() {
			return code;
		}

		@Override
		public long getDefaultIntervalSeconds() {
			return interval;
		}

		@Override
		public Duration getFirstDelay() {
			return Duration.ofSeconds(firstDelay);
		}

		@Override
		public LinkJobRun run() {
			runs++;
			thread = Thread.currentThread().getName();
			if (failure != null) {
				throw failure;
			}
			return next;
		}
	}

	@BeforeEach
	void setUp() {
		LinkJobStateRepository jobs = (LinkJobStateRepository) Proxy.newProxyInstance(
				LinkJobStateRepository.class.getClassLoader(), new Class<?>[] { LinkJobStateRepository.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "findAll":
							return new ArrayList<>(jobTable.values());
						case "findByCode":
							return Optional.ofNullable(jobTable.get(args[0]));
						case "save":
							LinkJobState row = (LinkJobState) args[0];
							jobTable.put(row.getCode(), row);
							return row;
						default:
							throw new UnsupportedOperationException(method.getName());
					}
				});
		LinkExchangeRepository log = (LinkExchangeRepository) Proxy.newProxyInstance(
				LinkExchangeRepository.class.getClassLoader(), new Class<?>[] { LinkExchangeRepository.class },
				(proxy, method, args) -> {
					if ("deleteOlderThan".equals(method.getName())) {
						purges++;
						return 0;
					}
					throw new UnsupportedOperationException(method.getName());
				});
		jobService = new LinkJobService(jobs, TransactionOperations.withoutTransaction());
		exchangeLog = new LinkExchangeLog(log, TransactionOperations.withoutTransaction(), 30);
		scheduler = new LinkJobScheduler(Arrays.asList(heartbeat, push), jobService, exchangeLog);
		logs = new ListAppender<>();
		logs.start();
		logger().addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		scheduler.stop();
		logger().detachAppender(logs);
	}

	private static Logger logger() {
		return (Logger) LoggerFactory.getLogger(LinkJobScheduler.class);
	}

	private List<String> lines(Level level) {
		return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.toList());
	}

	private static TriggerContext completedAt(Date lastCompletion) {
		return new TriggerContext() {
			@Override
			public Date lastScheduledExecutionTime() {
				return lastCompletion;
			}

			@Override
			public Date lastActualExecutionTime() {
				return lastCompletion;
			}

			@Override
			public Date lastCompletionTime() {
				return lastCompletion;
			}
		};
	}

	@Test
	@DisplayName("Timing: first run at the first delay, then the default frequency after the end of the previous run")
	void timing() {
		Instant first = Instant.parse("2026-10-03T10:00:15Z");
		assertEquals(Date.from(first), scheduler.nextRun(heartbeat, completedAt(null), first));
		Date end = new Date(1_000_000L);
		assertEquals(new Date(1_000_000L + 60_000L), scheduler.nextRun(heartbeat, completedAt(end), first));
		assertEquals(new Date(1_000_000L + 120_000L), scheduler.nextRun(push, completedAt(end), first));
	}

	@Test
	@DisplayName("A frequency saved from the page is used by the next run, saved in hol_job; back to the default with null")
	void frequencyChange() {
		Date end = new Date(1_000_000L);
		jobService.setInterval(push, 300L);

		assertEquals(new Date(1_000_000L + 300_000L), scheduler.nextRun(push, completedAt(end), Instant.now()));
		assertEquals(Long.valueOf(300), jobTable.get("SALES_PUSH").getIntervalSeconds());
		assertEquals(new Date(1_000_000L + 60_000L), scheduler.nextRun(heartbeat, completedAt(end), Instant.now()),
				"the other job keeps its own frequency");

		jobService.setInterval(push, null);
		assertEquals(new Date(1_000_000L + 120_000L), scheduler.nextRun(push, completedAt(end), Instant.now()));
		assertNull(jobTable.get("SALES_PUSH").getIntervalSeconds());
	}

	@Test
	@DisplayName("A run with more to do brings the next run 5 s later; then back to the frequency")
	void catchUp() {
		push.next = LinkJobRun.of(LinkJobResult.SUCCESS, "100 sent", true);
		scheduler.execute(push);
		assertEquals(Duration.ofSeconds(5), scheduler.nextDelay(push));
		push.next = LinkJobRun.success("nothing to send");
		scheduler.execute(push);
		assertEquals(Duration.ofSeconds(120), scheduler.nextDelay(push));
	}

	private static final String SLOW = "CATALOGUE: not delivered, the store keeps its last copy (TIMEOUT: head office"
			+ " answered too slowly (read timeout 60 s))";

	private String lastMessage(FakeJob job) {
		return jobService.state(job.getCode()).getLastMessage();
	}

	@Test
	@DisplayName("3.1.0 retry: fail, fail, success: 10 s, 10 s, then the frequency, the count reset (the next failure is 1/3)")
	void retryThenSuccess() {
		push.next = LinkJobRun.retryable(LinkJobResult.ERROR, SLOW);
		scheduler.execute(push);
		assertEquals(Duration.ofSeconds(10), scheduler.nextDelay(push));
		assertEquals(SLOW + ", retry in 10 s (1/3)", lastMessage(push), "the Link page's last message");
		scheduler.execute(push);
		assertEquals(Duration.ofSeconds(10), scheduler.nextDelay(push));
		assertEquals(SLOW + ", retry in 10 s (2/3)", lastMessage(push));

		push.next = LinkJobRun.of(LinkJobResult.SUCCESS, "CATALOGUE: 1000 applied", true);
		scheduler.execute(push);
		assertEquals(Duration.ofSeconds(5), scheduler.nextDelay(push), "a success keeps its rule: 5 s while more to do");
		push.next = LinkJobRun.success("CATALOGUE: nothing new");
		scheduler.execute(push);
		assertEquals(Duration.ofSeconds(120), scheduler.nextDelay(push), "else its frequency");

		push.next = LinkJobRun.retryable(LinkJobResult.ERROR, SLOW);
		scheduler.execute(push);
		assertEquals(SLOW + ", retry in 10 s (1/3)", lastMessage(push), "the count started again");
		assertTrue(lines(Level.WARN).isEmpty(), "no chain stopped");
	}

	@Test
	@DisplayName("3.1.0 retry: 4 failures in a row: 3 retries 10 s apart, the 4th back to the frequency with one WARN line;"
			+ " then a new chain")
	void fourFailuresBackToFrequency() {
		push.next = LinkJobRun.retryable(LinkJobResult.ERROR, SLOW);
		for (int failure = 1; failure <= 3; failure++) {
			scheduler.execute(push);
			assertEquals(Duration.ofSeconds(10), scheduler.nextDelay(push), "failure " + failure);
		}
		assertTrue(lines(Level.WARN).isEmpty());

		scheduler.execute(push);
		assertEquals(Duration.ofSeconds(120), scheduler.nextDelay(push), "the 4th: the frequency, as before 3.1.0");
		assertEquals(SLOW + ", 3 retries failed, next run at the frequency (120 s)", lastMessage(push));
		List<String> warnings = lines(Level.WARN);
		assertEquals(1, warnings.size());
		assertTrue(warnings.get(0).startsWith("Head office link: SALES_PUSH failed 4 times in a row, retries stopped;"
				+ " next run at its frequency (120 s)"), warnings.get(0));

		scheduler.execute(push);
		assertEquals(Duration.ofSeconds(10), scheduler.nextDelay(push), "the next period starts a new chain");
		assertEquals(SLOW + ", retry in 10 s (1/3)", lastMessage(push));
	}

	@Test
	@DisplayName("3.1.0 retry: an error that is not a timeout or an I/O error (HTTP 500) keeps the frequency and resets"
			+ " the count; a frequency change too")
	void otherErrorNoRetry() {
		push.next = LinkJobRun.retryable(LinkJobResult.ERROR, SLOW);
		scheduler.execute(push);
		push.next = LinkJobRun.error("CATALOGUE: unexpected answer from the head office: HTTP 500");
		scheduler.execute(push);
		assertEquals(Duration.ofSeconds(120), scheduler.nextDelay(push));
		assertEquals("CATALOGUE: unexpected answer from the head office: HTTP 500", lastMessage(push), "no retry text");

		push.next = LinkJobRun.retryable(LinkJobResult.ERROR, SLOW);
		scheduler.execute(push);
		assertEquals(SLOW + ", retry in 10 s (1/3)", lastMessage(push));
		scheduler.reschedule(push);
		assertEquals(Duration.ofSeconds(120), scheduler.nextDelay(push), "a frequency change ends the retries");
	}

	@Test
	@DisplayName("Each run is recorded (start, result, message, duration) in memory and hol_job; the log is purged at most once a day")
	void runRecorded() {
		LocalDateTime before = LocalDateTime.now().minusSeconds(1);
		push.next = LinkJobRun.of(LinkJobResult.WARNING, "3 sent, 1 rejected, 0 not built, 0 unchanged", false);

		scheduler.execute(push);
		scheduler.execute(push);

		LinkJobDTO view = scheduler.view(push);
		assertEquals(LinkJobResult.WARNING, view.getLastResult());
		assertEquals("3 sent, 1 rejected, 0 not built, 0 unchanged", view.getLastMessage());
		assertTrue(view.getLastRunAt().isAfter(before));
		assertNotNull(view.getLastDurationMs());
		assertEquals(LinkJobResult.WARNING, jobTable.get("SALES_PUSH").getLastResult());
		assertEquals(1, purges, "purged at the first run only, then once a day");
	}

	@Test
	@DisplayName("A job that throws: ERROR run with the cause, WARN line, no exception; the next run at its usual time")
	void jobThrows() {
		push.failure = new IllegalStateException("boom");

		LinkJobRun run = scheduler.execute(push);

		assertEquals(LinkJobResult.ERROR, run.getResult());
		assertEquals("job failed (IllegalStateException: boom)", run.getMessage());
		assertEquals(LinkJobResult.ERROR, scheduler.view(push).getLastResult());
		assertEquals(Duration.ofSeconds(120), scheduler.nextDelay(push));
		assertEquals(1, lines(Level.WARN).size());
	}

	@Test
	@DisplayName("Start: one thread ho-link-1 (a second start changes nothing), no run at once, one INFO line with the jobs")
	void start() {
		scheduler.start();
		scheduler.start();

		assertTrue(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("ho-link-1")));
		assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("ho-link-2")));
		assertEquals(0, heartbeat.runs + push.runs, "first runs after 15 and 20 s");
		assertEquals(Arrays.asList("Head office link: jobs on ho-link-1: HEARTBEAT every 60 s, SALES_PUSH every 120 s"),
				lines(Level.INFO));
		LocalDateTime nextRun = scheduler.view(heartbeat).getNextRunAt();
		assertTrue(nextRun.isAfter(LocalDateTime.now().plusSeconds(10)), String.valueOf(nextRun));
	}

	@Test
	@DisplayName("Run now: refused before the start (no run); after it, runs on ho-link-1, waited for, recorded")
	void runNow() {
		assertFalse(scheduler.runNow(push));
		assertEquals(0, push.runs);

		scheduler.start();
		assertTrue(scheduler.runNow(push));

		assertEquals(1, push.runs);
		assertEquals("ho-link-1", push.thread);
		assertEquals(LinkJobResult.SUCCESS, scheduler.view(push).getLastResult());
	}

	@Test
	@DisplayName("Run now with more to do: the next run 5 s after it, not at the time the trigger had computed; the"
			+ " catch-up goes on")
	void runNowWithMoreToDo() {
		scheduler.start();
		jobService.setInterval(push, 1800L);
		push.next = LinkJobRun.of(LinkJobResult.SUCCESS, "1000 copied, more to come", true);

		assertTrue(scheduler.runNow(push));

		long seconds = ChronoUnit.SECONDS.between(LocalDateTime.now(), scheduler.view(push).getNextRunAt());
		assertTrue(seconds >= 0 && seconds <= 5, "next run in " + seconds + " s, not the 20 s first delay nor 30 min");
		assertEquals(Duration.ofSeconds(5), scheduler.nextDelay(push), "the trigger keeps catching up");
	}

	@Test
	@DisplayName("Run now with nothing more: the next run one frequency after it, as after a scheduled run; the other"
			+ " jobs keep their time")
	void runNowCaughtUp() {
		scheduler.start();
		LocalDateTime heartbeatBefore = scheduler.view(heartbeat).getNextRunAt();
		push.next = LinkJobRun.of(LinkJobResult.SUCCESS, "1000 copied, more to come", true);
		scheduler.execute(push); // a scheduled run that was catching up
		push.next = LinkJobRun.success("nothing more");

		assertTrue(scheduler.runNow(push));

		LocalDateTime expected = LocalDateTime.now().plusSeconds(120);
		assertTrue(Math.abs(ChronoUnit.SECONDS.between(scheduler.view(push).getNextRunAt(), expected)) <= 5,
				String.valueOf(scheduler.view(push).getNextRunAt()));
		assertEquals(Duration.ofSeconds(120), scheduler.nextDelay(push));
		assertTrue(Math.abs(ChronoUnit.SECONDS.between(scheduler.view(heartbeat).getNextRunAt(), heartbeatBefore)) <= 1,
				"the other job untouched");
	}

	@Test
	@DisplayName("Reschedule after a change: the next run comes one new frequency from now; views in order with the limits")
	void rescheduleAndViews() {
		scheduler.start();
		jobService.setInterval(push, 3600L);
		scheduler.reschedule(push);

		LinkJobDTO view = scheduler.view(push);
		assertEquals(3600, view.getIntervalSeconds());
		assertEquals(120, view.getDefaultIntervalSeconds());
		assertTrue(view.isCustomInterval());
		assertEquals(10, view.getMinimumIntervalSeconds());
		assertEquals(86_400, view.getMaximumIntervalSeconds());
		LocalDateTime expected = LocalDateTime.now().plusSeconds(3600);
		assertTrue(Math.abs(ChronoUnit.SECONDS.between(view.getNextRunAt(), expected)) <= 5, String.valueOf(view.getNextRunAt()));

		assertEquals(Arrays.asList("HEARTBEAT", "SALES_PUSH"),
				scheduler.views().stream().map(LinkJobDTO::getCode).collect(Collectors.toList()));
		assertFalse(scheduler.views().get(0).isCustomInterval());
		assertTrue(scheduler.job(" sales_push ").isPresent());
		assertFalse(scheduler.job("PROMOTIONS").isPresent());
	}
}
