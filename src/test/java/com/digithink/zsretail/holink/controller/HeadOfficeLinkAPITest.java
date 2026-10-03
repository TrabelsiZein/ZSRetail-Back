package com.digithink.zsretail.holink.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.HeadOfficeLinkStatusDTO;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.scheduler.HeadOfficeHeartbeatScheduler;
import com.digithink.zsretail.holink.service.HeadOfficeLinkStatus;
import com.digithink.zsretail.service.GeneralSetupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Head office plan, task 1.5: GET admin/holink/status answers the link status with the URL, the store code and the
 * interval, never the key; POST admin/holink/check runs one heartbeat on the ho-link thread and answers the new state.
 * Real client, status and scheduler over MockRestServiceServer, no Spring context.
 */
class HeadOfficeLinkAPITest {

	private static final String KEY = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
	private static final String HEARTBEAT = "http://localhost:888/zsretail/api/ho/heartbeat";
	private static final List<String> KEYS = Arrays.asList("state", "message", "lastAttempt", "lastSuccess",
			"serverTime", "headOfficeUrl", "storeCode", "intervalSeconds");

	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	private String location = "SHOWROOM-S";
	private volatile String callThread;
	private MockRestServiceServer server;
	private HeadOfficeHeartbeatScheduler scheduler;
	private HeadOfficeLinkAPI api;

	@BeforeEach
	void setUp() {
		RestTemplate restTemplate = new RestTemplate();
		server = MockRestServiceServer.bindTo(restTemplate).build();
		GeneralSetupService generalSetup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return location;
			}
		};
		HeadOfficeClient client = new HeadOfficeClient(restTemplate, generalSetup, "http://localhost:888/zsretail/api/",
				KEY, "1.12.0");
		HeadOfficeLinkStatus status = new HeadOfficeLinkStatus();
		scheduler = new HeadOfficeHeartbeatScheduler(client, status, 60);
		api = new HeadOfficeLinkAPI(status, scheduler, client);
	}

	@AfterEach
	void tearDown() {
		scheduler.stop();
	}

	/** The answer as JSON: exactly the expected keys, and never the key. */
	private JsonNode json(HeadOfficeLinkStatusDTO answer) {
		JsonNode json = mapper.valueToTree(answer);
		List<String> keys = new ArrayList<>();
		json.fieldNames().forEachRemaining(keys::add);
		assertEquals(KEYS, keys);
		assertFalse(json.toString().contains(KEY), json.toString());
		assertFalse(json.toString().toLowerCase().contains("apikey"), json.toString());
		return json;
	}

	private ResponseCreator recordingThread(ResponseCreator response) {
		return request -> {
			callThread = Thread.currentThread().getName();
			return response.createResponse(request);
		};
	}

	@Test
	@DisplayName("Status at start: PENDING, URL without trailing slash, store code, interval; never the key")
	void statusAtStart() {
		JsonNode json = json(api.status());
		assertEquals("PENDING", json.get("state").asText());
		assertEquals("no heartbeat yet", json.get("message").asText());
		assertEquals("http://localhost:888/zsretail/api", json.get("headOfficeUrl").asText());
		assertEquals("SHOWROOM-S", json.get("storeCode").asText());
		assertEquals(60, json.get("intervalSeconds").asLong());
		assertNull(api.status().getLastAttempt());
	}

	@Test
	@DisplayName("Check before the heartbeat is started: no call, the status is unchanged")
	void checkBeforeStart() {
		assertEquals(HeadOfficeLinkState.PENDING, api.check().getState());
		server.verify(); // no request expected: any call would have failed
	}

	@Test
	@DisplayName("Check: one heartbeat on the ho-link thread, the answer is the new state (ONLINE, then REFUSED)")
	void checkRunsOnHeartbeatThread() {
		scheduler.start();
		server.expect(requestTo(HEARTBEAT)).andRespond(recordingThread(withSuccess(
				"{\"storeCode\":\"SHOWROOM-S\",\"serverTime\":\"2026-10-03T12:30:00.000+01:00\"}", MediaType.APPLICATION_JSON)));

		HeadOfficeLinkStatusDTO online = api.check();
		server.verify();
		assertEquals("ho-link-1", callThread, "the head office is called from the heartbeat thread only");
		assertEquals(HeadOfficeLinkState.ONLINE, online.getState());
		assertNull(online.getMessage());
		assertNotNull(online.getLastSuccess());
		assertEquals(online.getLastAttempt(), online.getLastSuccess());
		assertEquals("2026-10-03T12:30:00.000+01:00", online.getServerTime());
		json(online);
		assertEquals(HeadOfficeLinkState.ONLINE, api.status().getState(), "GET status shows the same state");

		server.reset();
		server.expect(requestTo(HEARTBEAT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
		HeadOfficeLinkStatusDTO refused = api.check();
		server.verify();
		assertEquals(HeadOfficeLinkState.REFUSED, refused.getState());
		assertEquals("store code or key refused by the head office", refused.getMessage());
		assertEquals(online.getLastSuccess(), refused.getLastSuccess(), "last success kept");
		json(refused);
	}

	@Test
	@DisplayName("DEFAULT_LOCATION empty: check gives NOT_CONFIGURED without a call, store code null")
	void notConfigured() {
		scheduler.start();
		location = "  ";
		HeadOfficeLinkStatusDTO answer = api.check();
		server.verify();
		assertEquals(HeadOfficeLinkState.NOT_CONFIGURED, answer.getState());
		assertNull(answer.getStoreCode());
		assertNull(json(answer).get("storeCode").textValue());
	}
}
