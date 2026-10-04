package com.digithink.zsretail.holink.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.DownApplyResult;
import com.digithink.zsretail.holink.dto.HeadOfficeLinkStatusDTO;
import com.digithink.zsretail.holink.dto.LinkJobDTO;
import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.holink.repository.DownRecordRepository;
import com.digithink.zsretail.holink.service.CopiesDownPuller;
import com.digithink.zsretail.holink.service.DownHandler;
import com.digithink.zsretail.holink.service.DownRecordLog;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.repository.SalesCopyRepository;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.holink.model.LinkJobState;
import com.digithink.zsretail.holink.repository.LinkExchangeRepository;
import com.digithink.zsretail.holink.repository.LinkJobStateRepository;
import com.digithink.zsretail.holink.scheduler.HeartbeatJob;
import com.digithink.zsretail.holink.scheduler.LinkJobScheduler;
import com.digithink.zsretail.holink.service.LinkExchangeLog;
import com.digithink.zsretail.holink.service.LinkJobService;
import com.digithink.zsretail.holink.service.HeadOfficeLinkStatus;
import com.digithink.zsretail.holink.service.SalesPushService;
import com.digithink.zsretail.service.GeneralSetupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Head office plan, task 1.5: GET admin/holink/status answers the link status with the URL, the store code and the
 * interval, never the key; POST admin/holink/check runs one heartbeat on the ho-link thread and answers the new state.
 * Task 2.4: the sales copy counts, after the existing fields.
 * Task 2.6: the jobs (list, frequency, run now) and the exchange log endpoints.
 * Task 3.5: the counts of the records received from the head office (last field) and their list.
 * Real client, status, heartbeat job and job scheduler over MockRestServiceServer, in-memory hol_ tables, no Spring
 * context.
 */
class HeadOfficeLinkAPITest {

	private static final String KEY = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde";
	private static final String HEARTBEAT = "http://localhost:888/zsretail/api/ho/heartbeat";
	private static final List<String> KEYS = Arrays.asList("state", "message", "lastAttempt", "lastSuccess",
			"serverTime", "headOfficeUrl", "storeCode", "intervalSeconds", "pendingCount", "sentCount", "errorCount",
			"received", "loyalty", "catalogue", "supply");

	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	private String location = "SHOWROOM-S";
	private volatile String callThread;
	private MockRestServiceServer server;
	private LinkJobScheduler scheduler;
	private LinkJobService jobService;
	private LinkExchangeLog exchangeLog;
	private HeadOfficeClient client;
	private HeadOfficeLinkStatus status;
	private HeadOfficeLinkAPI api;
	private final Map<String, LinkJobState> jobTable = new LinkedHashMap<>();
	private final List<LinkExchange> logTable = new ArrayList<>();

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
		client = new HeadOfficeClient(restTemplate, generalSetup, "http://localhost:888/zsretail/api/", KEY, "1.12.0");
		status = new HeadOfficeLinkStatus();
		jobService = new LinkJobService(proxy(LinkJobStateRepository.class, (method, args) -> {
			switch (method) {
				case "findAll":
					return new ArrayList<>(jobTable.values());
				case "findByCode":
					return Optional.ofNullable(jobTable.get(args[0]));
				default: // save
					LinkJobState row = (LinkJobState) args[0];
					jobTable.put(row.getCode(), row);
					return row;
			}
		}), TransactionOperations.withoutTransaction());
		exchangeLog = new LinkExchangeLog(proxy(LinkExchangeRepository.class, (method, args) -> {
			switch (method) {
				case "save":
					logTable.add((LinkExchange) args[0]);
					return args[0];
				case "search":
					return new PageImpl<>(new ArrayList<>(logTable), (Pageable) args[4], logTable.size());
				default: // deleteOlderThan
					return 0;
			}
		}), TransactionOperations.withoutTransaction(), 30);
		HeartbeatJob heartbeat = new HeartbeatJob(client, status, exchangeLog, 60);
		scheduler = new LinkJobScheduler(Collections.singletonList(heartbeat), jobService, exchangeLog);
		api = new HeadOfficeLinkAPI(status, scheduler, jobService, exchangeLog, client, Optional.empty(),
				Optional.empty(), Optional.empty());
	}

	private interface Handler {
		Object handle(String method, Object[] args);
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
				(proxy, method, args) -> handler.handle(method.getName(), args));
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
		assertTrue(json.get("pendingCount").isNull(), "no sales push on this store: no counts");
		assertTrue(json.get("sentCount").isNull());
		assertTrue(json.get("errorCount").isNull());
		assertTrue(json.get("received").isNull(), "task 3.5: no pull on this store: no counts");
		assertTrue(json.get("loyalty").isNull(), "step 4: loyalty not owned by the head office: no block");
		assertTrue(json.get("catalogue").isNull(), "step 6: catalogue not the head office's: no block");
		assertTrue(json.get("supply").isNull(), "step 7A: goods not from the head office: no block");
	}

	@Test
	@DisplayName("Task 2.4: with the sales push, the counts per status, 0 for a status without rows; a failed count gives null")
	void salesCopyCounts() {
		List<Object[]> rows = new ArrayList<>();
		rows.add(new Object[] { SalesCopyStatus.PENDING, 12L });
		rows.add(new Object[] { SalesCopyStatus.SENT, 963L });
		boolean[] fail = { false };
		SalesCopyRepository repository = (SalesCopyRepository) Proxy.newProxyInstance(
				SalesCopyRepository.class.getClassLoader(), new Class<?>[] { SalesCopyRepository.class },
				(proxy, method, args) -> {
					if (!"countByStatus".equals(method.getName())) {
						throw new UnsupportedOperationException(method.getName());
					}
					if (fail[0]) {
						throw new IllegalStateException("database down");
					}
					return rows;
				});
		SalesPushService push = new SalesPushService(null, null, repository, null, null, null, null, null);
		HeadOfficeLinkAPI withPush = new HeadOfficeLinkAPI(status, scheduler, jobService, exchangeLog, client,
				Optional.of(push), Optional.empty(), Optional.empty());

		JsonNode json = json(withPush.status());
		assertEquals(12, json.get("pendingCount").asLong());
		assertEquals(963, json.get("sentCount").asLong());
		assertEquals(0, json.get("errorCount").asLong());
		assertEquals("PENDING", json.get("state").asText(), "the link fields are unchanged");

		fail[0] = true;
		assertNull(withPush.status().getPendingCount());
		assertEquals(HeadOfficeLinkState.PENDING, withPush.status().getState());
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

	// --- Task 2.6: jobs and exchange log ---

	private static Map<String, Object> body(Object seconds) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("intervalSeconds", seconds);
		return body;
	}

	@Test
	@DisplayName("Task 2.6: GET jobs lists the jobs of this store with their frequency and limits (only the heartbeat without the push)")
	void jobsList() {
		List<LinkJobDTO> jobs = api.jobs();
		assertEquals(1, jobs.size());
		LinkJobDTO heartbeat = jobs.get(0);
		assertEquals("HEARTBEAT", heartbeat.getCode());
		assertEquals(60, heartbeat.getIntervalSeconds());
		assertFalse(heartbeat.isCustomInterval());
		assertNull(heartbeat.getLastRunAt());
		JsonNode json = mapper.valueToTree(heartbeat);
		List<String> keys = new ArrayList<>();
		json.fieldNames().forEachRemaining(keys::add);
		assertEquals(Arrays.asList("code", "intervalSeconds", "defaultIntervalSeconds", "customInterval",
				"minimumIntervalSeconds", "maximumIntervalSeconds", "lastRunAt", "lastResult", "lastMessage",
				"lastDurationMs", "nextRunAt"), keys);
	}

	@Test
	@DisplayName("Task 2.6: PUT jobs/{code}/interval saves the frequency (status shows it), null resets; 400 outside the limits or not a number; 404 unknown job")
	void setInterval() {
		ResponseEntity<?> saved = api.setInterval("heartbeat", body(120));
		assertEquals(HttpStatus.OK, saved.getStatusCode());
		assertEquals(120, ((LinkJobDTO) saved.getBody()).getIntervalSeconds());
		assertTrue(((LinkJobDTO) saved.getBody()).isCustomInterval());
		assertEquals(Long.valueOf(120), jobTable.get("HEARTBEAT").getIntervalSeconds());
		assertEquals(120, api.status().getIntervalSeconds(), "the status shows the frequency in force");

		ResponseEntity<?> tooLow = api.setInterval("HEARTBEAT", body(5));
		assertEquals(HttpStatus.BAD_REQUEST, tooLow.getStatusCode());
		assertEquals(Collections.singletonMap("error", "intervalSeconds must be from 10 to 86400 seconds"), tooLow.getBody());
		assertEquals(HttpStatus.BAD_REQUEST, api.setInterval("HEARTBEAT", body("ten")).getStatusCode());
		assertEquals(120, api.status().getIntervalSeconds(), "a refused value changes nothing");

		assertEquals(HttpStatus.OK, api.setInterval("HEARTBEAT", body(null)).getStatusCode());
		assertEquals(60, api.status().getIntervalSeconds());

		ResponseEntity<?> unknown = api.setInterval("SALES_PUSH", body(60));
		assertEquals(HttpStatus.NOT_FOUND, unknown.getStatusCode(), "no push job on this store");
	}

	@Test
	@DisplayName("Task 2.6: POST jobs/{code}/run runs the job on ho-link-1 and answers it after the run; 404 unknown job")
	void runNow() {
		scheduler.start();
		server.expect(requestTo(HEARTBEAT)).andRespond(recordingThread(withSuccess(
				"{\"storeCode\":\"SHOWROOM-S\",\"serverTime\":\"2026-10-03T12:30:00.000+01:00\"}", MediaType.APPLICATION_JSON)));

		ResponseEntity<?> answer = api.runNow("HEARTBEAT");

		server.verify();
		assertEquals("ho-link-1", callThread);
		LinkJobDTO job = (LinkJobDTO) answer.getBody();
		assertEquals(LinkJobResult.SUCCESS, job.getLastResult());
		assertEquals("ONLINE", job.getLastMessage());
		assertNotNull(job.getLastRunAt());
		assertEquals(1, logTable.size(), "PENDING -> ONLINE: one exchange row");
		assertEquals(HttpStatus.NOT_FOUND, api.runNow("PROMOTIONS").getStatusCode());
	}

	@Test
	@DisplayName("Task 2.6: GET log answers the page; 400 on a filter that cannot be read")
	void log() {
		exchangeLog.record("HEARTBEAT", com.digithink.zsretail.holink.enumeration.ExchangeDirection.UP, 0,
				LinkJobResult.SUCCESS, null, java.time.LocalDateTime.of(2026, 10, 3, 12, 0), 12);

		ResponseEntity<?> page = api.log(null, null, null, null, null, null);
		assertEquals(HttpStatus.OK, page.getStatusCode());
		assertEquals(1L, ((Map<?, ?>) page.getBody()).get("totalElements"));
		assertEquals(HttpStatus.BAD_REQUEST, api.log(null, "BROKEN", null, null, null, null).getStatusCode());
		assertEquals(HttpStatus.BAD_REQUEST, api.log(null, null, "yesterday", null, null, null).getStatusCode());
	}

	@Test
	@DisplayName("Task 3.5: received counts per domain pulled (last field), the list ERROR, WAITING then APPLIED, filters, 404 and 400")
	void receivedRecords() {
		List<DownRecord> rows = new ArrayList<>();
		rows.add(row("P-B", DownRecordStatus.APPLIED, null, "group items not in this store: I9"));
		rows.add(row("P-A", DownRecordStatus.APPLIED, null, null));
		rows.add(row("P-W", DownRecordStatus.WAITING, "not in this store: item I9", null));
		DownRecordRepository repository = proxy(DownRecordRepository.class, (method, args) -> {
			switch (method) {
				case "findByDomainAndStatusIn":
					java.util.Collection<?> statuses = (java.util.Collection<?>) args[1];
					return rows.stream().filter(r -> r.getDomain() == args[0] && statuses.contains(r.getStatus()))
							.collect(Collectors.toList());
				case "countByStatus":
					Map<DownRecordStatus, Long> counts = rows.stream().filter(r -> r.getDomain() == args[0])
							.collect(Collectors.groupingBy(DownRecord::getStatus, Collectors.counting()));
					return counts.entrySet().stream().map(e -> new Object[] { e.getKey(), e.getValue() })
							.collect(Collectors.toList());
				default:
					throw new UnsupportedOperationException(method);
			}
		});
		DownHandler promotions = new DownHandler() {
			@Override
			public DataDomain getDomain() {
				return DataDomain.PROMOTIONS;
			}

			@Override
			public DownApplyResult apply(List<JsonNode> records, List<String> removed) {
				return DownApplyResult.none();
			}
		};
		CopiesDownPuller puller = new CopiesDownPuller(client, Collections.singletonList(promotions), null, exchangeLog,
				TransactionOperations.withoutTransaction());
		HeadOfficeLinkAPI withPull = new HeadOfficeLinkAPI(status, scheduler, jobService, exchangeLog, client,
				Optional.empty(), Optional.of(puller), Optional.of(new DownRecordLog(repository)));

		JsonNode json = json(withPull.status());
		assertEquals("{\"PROMOTIONS\":{\"APPLIED\":2,\"WAITING\":1,\"ERROR\":0}}", json.get("received").toString());

		ResponseEntity<?> all = withPull.received("promotions", null);
		assertEquals(200, all.getStatusCodeValue());
		JsonNode list = mapper.valueToTree(all.getBody());
		assertEquals("PROMOTIONS", list.get("domain").asText());
		assertEquals(2, list.get("counts").get("APPLIED").asLong());
		List<String> codes = new ArrayList<>();
		list.get("records").forEach(r -> codes.add(r.get("code").asText()));
		assertEquals(Arrays.asList("P-W", "P-A", "P-B"), codes);
		JsonNode waiting = list.get("records").get(0);
		assertEquals("WAITING", waiting.get("status").asText());
		assertEquals("not in this store: item I9", waiting.get("reason").asText());
		List<String> fields = new ArrayList<>();
		waiting.fieldNames().forEachRemaining(fields::add);
		assertEquals(Arrays.asList("code", "name", "status", "reason", "info", "receivedAt", "statusSince"), fields);

		JsonNode onlyWaiting = mapper.valueToTree(withPull.received("PROMOTIONS", "waiting").getBody());
		assertEquals(1, onlyWaiting.get("records").size());
		assertEquals(400, withPull.received("PROMOTIONS", "LATE").getStatusCodeValue());
		assertEquals(404, withPull.received("LOYALTY", null).getStatusCodeValue());
		assertEquals(404, api.received("PROMOTIONS", null).getStatusCodeValue(), "no pull on this store");
	}

	private static DownRecord row(String code, DownRecordStatus status, String reason, String info) {
		DownRecord row = new DownRecord();
		row.setDomain(DataDomain.PROMOTIONS);
		row.setRecordCode(code);
		row.setRecordName("Promo " + code);
		row.setStatus(status);
		row.setReason(reason);
		row.setInfo(info);
		row.setReceivedAt(LocalDateTime.of(2026, 10, 3, 9, 0));
		row.setStatusSince(LocalDateTime.of(2026, 10, 3, 9, 0));
		return row;
	}
}
