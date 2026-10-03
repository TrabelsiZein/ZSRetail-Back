package com.digithink.zsretail.holink.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TriggerContext;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.service.HeadOfficeLinkStatus;
import com.digithink.zsretail.holink.service.SalesPushService;
import com.digithink.zsretail.holink.service.SalesPushSettings;
import com.digithink.zsretail.service.GeneralSetupService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Head office plan, task 2.4: the push runs on the ho-link thread, after the heartbeat (first cycle 20 s after the
 * start, the first heartbeat being at 15 s), then every interval read at each cycle, or 5 s later while catching up.
 * A cycle never throws. No Spring context; the heartbeat makes no call during the test (its first one is at 15 s).
 */
class SalesPushSchedulerTest {

	private HeadOfficeHeartbeatScheduler linkThread;
	private ListAppender<ILoggingEvent> logs;

	/** The interval as task 2.6 will make it: read at each cycle. */
	private long interval = 60;

	private final SalesPushSettings settings = new SalesPushSettings("", 50, 60) {
		@Override
		public long getIntervalSeconds() {
			return interval;
		}
	};

	@BeforeEach
	void setUp() {
		GeneralSetupService generalSetup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "RS01";
			}
		};
		HeadOfficeClient client = new HeadOfficeClient(new RestTemplate(), generalSetup,
				"http://localhost:888/zsretail/api", "key", "1.12.0");
		linkThread = new HeadOfficeHeartbeatScheduler(client, new HeadOfficeLinkStatus(), 60);
		logs = new ListAppender<>();
		logs.start();
		logger().addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		linkThread.stop();
		logger().detachAppender(logs);
	}

	private static Logger logger() {
		return (Logger) LoggerFactory.getLogger(SalesPushScheduler.class);
	}

	private List<String> lines(Level level) {
		return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.toList());
	}

	/** A service whose cycle throws: the scheduler must not. */
	private static SalesPushService failingService() {
		return new SalesPushService(null, null, null, null, null, null, null, null) {
			@Override
			public Cycle runCycle() {
				throw new IllegalStateException("hol_sales_copy unreadable");
			}
		};
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
	@DisplayName("First cycle 20 s after the start, then the interval after the end of the previous cycle, read at each cycle")
	void timing() {
		SalesPushScheduler scheduler = new SalesPushScheduler(failingService(), settings, linkThread);
		Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS); // the trigger answers a Date (milliseconds)
		scheduler.start();

		Date first = scheduler.nextRun(completedAt(null));
		assertTrue(!first.toInstant().isBefore(before.plusSeconds(20)), "after the first heartbeat (15 s)");
		assertTrue(!first.toInstant().isAfter(Instant.now().plusSeconds(20)));

		Date end = new Date(1_000_000L);
		assertEquals(new Date(1_000_000L + 60_000L), scheduler.nextRun(completedAt(end)));
		interval = 300; // changed at runtime (task 2.6)
		assertEquals(new Date(1_000_000L + 300_000L), scheduler.nextRun(completedAt(end)));
		assertEquals(Duration.ofSeconds(300), scheduler.nextDelay());
		assertEquals(Duration.ofSeconds(20), SalesPushScheduler.FIRST_DELAY);
		assertEquals(Duration.ofSeconds(5), SalesPushScheduler.CATCH_UP_DELAY);
		assertEquals(1, lines(Level.INFO).size(), lines(Level.INFO).toString());
		assertEquals("Head office sales push: every 60 s, 50 documents per request, whole history",
				lines(Level.INFO).get(0), "logged at start, with the interval of that moment");
	}

	@Test
	@DisplayName("A cycle that throws is logged; the scheduler does not throw and keeps the usual interval")
	void neverThrows() {
		SalesPushScheduler scheduler = new SalesPushScheduler(failingService(), settings, linkThread);

		scheduler.cycle();

		assertEquals(Duration.ofSeconds(60), scheduler.nextDelay());
		assertEquals(1, lines(Level.WARN).size());
		assertTrue(lines(Level.WARN).get(0).startsWith("Head office sales push: cycle failed, retried at the next cycle"
				+ " (IllegalStateException: hol_sales_copy unreadable)"), lines(Level.WARN).get(0));
	}

	@Test
	@DisplayName("Jobs scheduled on the link run on the single ho-link thread, the heartbeat's")
	void runsOnLinkThread() throws Exception {
		CountDownLatch ran = new CountDownLatch(1);
		AtomicReference<String> thread = new AtomicReference<>();
		boolean[] once = { false };

		linkThread.scheduleOnLinkThread(() -> {
			thread.set(Thread.currentThread().getName());
			ran.countDown();
		}, context -> {
			if (once[0]) {
				return null; // no further run
			}
			once[0] = true;
			return new Date();
		});

		assertTrue(ran.await(5, TimeUnit.SECONDS));
		assertEquals("ho-link-1", thread.get());
	}
}
