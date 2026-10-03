package com.digithink.zsretail.holink.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.service.HeadOfficeLinkStatus;
import com.digithink.zsretail.service.GeneralSetupService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Head office plan, task 1.4: each heartbeat updates the in-memory link status (a failure keeps the last success and
 * its head office time) and logs one INFO line when the state changes, DEBUG otherwise. The heartbeat runs on its own
 * thread and the first call is not made at start. Real HeadOfficeClient over MockRestServiceServer, log captured with
 * a Logback ListAppender, no Spring context.
 */
class HeadOfficeHeartbeatSchedulerTest {

	private static final String HEARTBEAT = "http://localhost:888/zsretail/api/ho/heartbeat";

	private MockRestServiceServer server;
	private HeadOfficeLinkStatus status;
	private HeadOfficeHeartbeatScheduler scheduler;
	private ListAppender<ILoggingEvent> logs;
	private Level previousLevel;

	@BeforeEach
	void setUp() {
		RestTemplate restTemplate = new RestTemplate();
		server = MockRestServiceServer.bindTo(restTemplate).build();
		GeneralSetupService generalSetup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "RS01";
			}
		};
		HeadOfficeClient client = new HeadOfficeClient(restTemplate, generalSetup, "http://localhost:888/zsretail/api",
				"AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde", "1.12.0");
		status = new HeadOfficeLinkStatus();
		scheduler = new HeadOfficeHeartbeatScheduler(client, status, 60);

		logs = new ListAppender<>();
		logs.start();
		previousLevel = logger().getLevel();
		logger().setLevel(Level.DEBUG);
		logger().addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		scheduler.stop();
		logger().detachAppender(logs);
		logger().setLevel(previousLevel);
	}

	private static Logger logger() {
		return (Logger) LoggerFactory.getLogger(HeadOfficeHeartbeatScheduler.class);
	}

	private List<String> lines(Level level) {
		return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.toList());
	}

	private void beat(ResponseCreator response) {
		server.reset();
		server.expect(requestTo(HEARTBEAT)).andRespond(response);
		scheduler.beat();
		server.verify();
	}

	private static ResponseCreator online(String serverTime) {
		return withSuccess("{\"storeCode\":\"RS01\",\"serverTime\":\"" + serverTime + "\"}", MediaType.APPLICATION_JSON);
	}

	private static ResponseCreator refused() {
		return request -> {
			throw new ConnectException("Connection refused");
		};
	}

	@Test
	@DisplayName("Status: PENDING at start; ONLINE sets last success; a failure keeps the last success and head office time")
	void statusFollowsHeartbeats() {
		HeadOfficeLinkStatus.Snapshot start = status.get();
		assertEquals(HeadOfficeLinkState.PENDING, start.getState());
		assertNull(start.getLastAttempt());
		assertNull(start.getLastSuccess());
		assertEquals("no heartbeat yet", start.getLastMessage());

		beat(online("2026-10-03T10:00:00.000+01:00"));
		HeadOfficeLinkStatus.Snapshot up = status.get();
		assertEquals(HeadOfficeLinkState.ONLINE, up.getState());
		assertNotNull(up.getLastAttempt());
		assertEquals(up.getLastAttempt(), up.getLastSuccess());
		assertNull(up.getLastMessage());
		assertEquals("2026-10-03T10:00:00.000+01:00", up.getServerTime());

		beat(refused());
		HeadOfficeLinkStatus.Snapshot down = status.get();
		assertEquals(HeadOfficeLinkState.OFFLINE, down.getState());
		assertFalse(down.getLastAttempt().isBefore(up.getLastAttempt()));
		assertEquals(up.getLastSuccess(), down.getLastSuccess(), "last success kept");
		assertEquals("2026-10-03T10:00:00.000+01:00", down.getServerTime(), "head office time of the last success kept");
		assertEquals("head office unreachable (ConnectException: Connection refused)", down.getLastMessage());

		beat(online("2026-10-03T10:01:00.000+01:00"));
		HeadOfficeLinkStatus.Snapshot back = status.get();
		assertEquals(HeadOfficeLinkState.ONLINE, back.getState());
		assertFalse(back.getLastSuccess().isBefore(down.getLastAttempt()));
		assertEquals("2026-10-03T10:01:00.000+01:00", back.getServerTime());
	}

	@Test
	@DisplayName("Log: one INFO line per state change, DEBUG while the state stays the same")
	void infoOnlyOnChange() {
		beat(online("2026-10-03T10:00:00.000+01:00")); // PENDING -> ONLINE
		beat(online("2026-10-03T10:01:00.000+01:00")); // same
		beat(refused());                               // ONLINE -> OFFLINE
		beat(refused());                               // same
		beat(withStatus(HttpStatus.UNAUTHORIZED));     // OFFLINE -> REFUSED
		beat(online("2026-10-03T10:05:00.000+01:00")); // REFUSED -> ONLINE

		List<String> info = lines(Level.INFO);
		assertEquals(4, info.size(), info.toString());
		assertEquals("Head office link: PENDING -> ONLINE (head office time 2026-10-03T10:00:00.000+01:00)", info.get(0));
		assertEquals("Head office link: ONLINE -> OFFLINE (head office unreachable (ConnectException: Connection refused))",
				info.get(1));
		assertEquals("Head office link: OFFLINE -> REFUSED (store code or key refused by the head office)", info.get(2));
		assertTrue(info.get(3).startsWith("Head office link: REFUSED -> ONLINE"), info.get(3));

		List<String> debug = lines(Level.DEBUG);
		assertEquals(2, debug.size(), debug.toString());
		assertEquals("Head office link: ONLINE (head office time 2026-10-03T10:01:00.000+01:00)", debug.get(0));
		assertTrue(debug.get(1).startsWith("Head office link: OFFLINE (head office unreachable"), debug.get(1));
	}

	@Test
	@DisplayName("start: own thread ho-link-1, no call at once (first heartbeat 15 s later); stop ends it")
	void ownThread() throws Exception {
		LocalDateTime before = LocalDateTime.now();
		scheduler.start();
		scheduler.start(); // a second ready event does not start a second thread
		assertTrue(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("ho-link-1")),
				"thread ho-link-1");
		assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().equals("ho-link-2")));
		assertEquals(15, HeadOfficeHeartbeatScheduler.FIRST_DELAY.getSeconds());
		assertTrue(lines(Level.INFO).contains("Head office link: heartbeat to http://localhost:888/zsretail/api every 60 s"),
				lines(Level.INFO).toString());

		scheduler.stop();
		server.verify(); // no request expected: any call would have failed
		assertEquals(HeadOfficeLinkState.PENDING, status.get().getState());
		assertTrue(LocalDateTime.now().isBefore(before.plusSeconds(15)), "checked before the first heartbeat");
	}
}
