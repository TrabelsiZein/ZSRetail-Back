package com.digithink.zsretail.holink.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.holink.model.LinkRight;
import com.digithink.zsretail.holink.repository.LinkExchangeRepository;
import com.digithink.zsretail.holink.repository.LinkRightRepository;
import com.digithink.zsretail.holink.service.CatalogueRights;
import com.digithink.zsretail.holink.service.HeadOfficeLinkStatus;
import com.digithink.zsretail.holink.service.LinkExchangeLog;
import com.digithink.zsretail.service.GeneralSetupService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Head office plan, task 1.4, a job since task 2.6: each heartbeat updates the in-memory link status (a failure keeps
 * the last success and its head office time) and logs one INFO line when the state changes, DEBUG otherwise. Task 2.6:
 * it writes an exchange log row only when its state changes, and its run result is SUCCESS when ONLINE, ERROR
 * otherwise. Real HeadOfficeClient over MockRestServiceServer, in-memory exchange log, no Spring context.
 */
class HeartbeatJobTest {

	private static final String HEARTBEAT = "http://localhost:888/zsretail/api/ho/heartbeat";

	private MockRestServiceServer server;
	private HeadOfficeLinkStatus status;
	private HeartbeatJob job;
	private final List<LinkExchange> logRows = new ArrayList<>();
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
		LinkExchangeRepository repository = (LinkExchangeRepository) Proxy.newProxyInstance(
				LinkExchangeRepository.class.getClassLoader(), new Class<?>[] { LinkExchangeRepository.class },
				(proxy, method, args) -> {
					if ("save".equals(method.getName())) {
						logRows.add((LinkExchange) args[0]);
						return args[0];
					}
					throw new UnsupportedOperationException(method.getName());
				});
		job = new HeartbeatJob(client, status, new LinkExchangeLog(repository, TransactionOperations.withoutTransaction(), 30),
				60);

		logs = new ListAppender<>();
		logs.start();
		previousLevel = logger().getLevel();
		logger().setLevel(Level.DEBUG);
		logger().addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		logger().detachAppender(logs);
		logger().setLevel(previousLevel);
	}

	private static Logger logger() {
		return (Logger) LoggerFactory.getLogger(HeartbeatJob.class);
	}

	private List<String> lines(Level level) {
		return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.toList());
	}

	private LinkJobRun beat(ResponseCreator response) {
		server.reset();
		server.expect(requestTo(HEARTBEAT)).andRespond(response);
		LinkJobRun run = job.run();
		server.verify();
		return run;
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
	@DisplayName("Job: code HEARTBEAT, first run 15 s after the start, default frequency from headoffice.heartbeat-interval-seconds")
	void job() {
		assertEquals("HEARTBEAT", job.getCode());
		assertEquals(15, job.getFirstDelay().getSeconds());
		assertEquals(60, job.getDefaultIntervalSeconds());
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
	@DisplayName("Log: one INFO line and one exchange row per state change; DEBUG and no row while the state stays the same")
	void onlyStateChanges() {
		beat(online("2026-10-03T10:00:00.000+01:00")); // PENDING -> ONLINE
		beat(online("2026-10-03T10:01:00.000+01:00")); // same
		beat(online("2026-10-03T10:02:00.000+01:00")); // same
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
		assertEquals(3, debug.size(), debug.toString());
		assertEquals("Head office link: ONLINE (head office time 2026-10-03T10:01:00.000+01:00)", debug.get(0));

		assertEquals(4, logRows.size(), "rows only on the 4 state changes");
		assertEquals(LinkJobResult.SUCCESS, logRows.get(0).getResult());
		assertNull(logRows.get(0).getError());
		assertEquals(LinkJobResult.ERROR, logRows.get(1).getResult());
		assertEquals("OFFLINE: head office unreachable (ConnectException: Connection refused)", logRows.get(1).getError());
		assertEquals("REFUSED: store code or key refused by the head office", logRows.get(2).getError());
		assertEquals(LinkJobResult.SUCCESS, logRows.get(3).getResult());
		for (LinkExchange row : logRows) {
			assertEquals("HEARTBEAT", row.getJob());
			assertEquals(ExchangeDirection.UP, row.getDirection());
			assertEquals(0, row.getRecordCount());
			assertNotNull(row.getExchangeDate());
			assertNotNull(row.getDurationMs());
		}
	}

	@Test
	@DisplayName("Run result: SUCCESS 'ONLINE', or ERROR with the state and message; never 'more to do'")
	void runResult() {
		LinkJobRun up = beat(online("2026-10-03T10:00:00.000+01:00"));
		assertEquals(LinkJobResult.SUCCESS, up.getResult());
		assertEquals("ONLINE", up.getMessage());
		assertFalse(up.isRunAgainSoon());

		LinkJobRun down = beat(refused());
		assertEquals(LinkJobResult.ERROR, down.getResult());
		assertEquals("OFFLINE: head office unreachable (ConnectException: Connection refused)", down.getMessage());
	}

	@Test
	@DisplayName("Step 6: with the catalogue owned by the head office, the rights of an ONLINE answer are saved; a failure"
			+ " or an answer without them keeps the saved values")
	void catalogueRightsSaved() throws Exception {
		Map<String, LinkRight> saved = new HashMap<>();
		LinkRightRepository repository = (LinkRightRepository) Proxy.newProxyInstance(
				LinkRightRepository.class.getClassLoader(), new Class<?>[] { LinkRightRepository.class },
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "findByCode":
							return Optional.ofNullable(saved.get(args[0]));
						case "save":
							saved.put(((LinkRight) args[0]).getCode(), (LinkRight) args[0]);
							return args[0];
						default:
							throw new UnsupportedOperationException(method.getName());
					}
				});
		CatalogueRights rights = new CatalogueRights(repository);
		Field field = HeartbeatJob.class.getDeclaredField("catalogueRights");
		field.setAccessible(true);
		field.set(job, new StaticListableBeanFactory(Collections.singletonMap("rights", rights))
				.getBeanProvider(CatalogueRights.class));

		beat(withSuccess("{\"storeCode\":\"RS01\",\"serverTime\":\"2026-10-04T10:00:00.000+01:00\","
				+ "\"mayChangePrices\":true,\"canPurchase\":true}", MediaType.APPLICATION_JSON));
		assertTrue(rights.mayChangePrices());
		assertTrue(rights.canPurchase());
		beat(refused());
		assertTrue(new CatalogueRights(repository).canPurchase(), "unreachable: the saved value");
		beat(online("2026-10-04T10:01:00.000+01:00"));
		assertTrue(new CatalogueRights(repository).canPurchase(), "an older head office: kept");
		beat(withSuccess("{\"storeCode\":\"RS01\",\"serverTime\":\"2026-10-04T10:02:00.000+01:00\","
				+ "\"mayChangePrices\":false,\"canPurchase\":true}", MediaType.APPLICATION_JSON));
		assertFalse(new CatalogueRights(repository).mayChangePrices());
	}
}
