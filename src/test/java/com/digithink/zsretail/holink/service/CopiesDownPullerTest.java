package com.digithink.zsretail.holink.service;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.DownApplyResult;
import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.model.DownCursor;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.holink.repository.DownCursorRepository;
import com.digithink.zsretail.holink.repository.LinkExchangeRepository;
import com.digithink.zsretail.holink.scheduler.CopiesDownJob;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.service.GeneralSetupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Head office plan, task 3.1: the store side of the copies down mechanism, through the job. The cursor is saved
 * exactly as the head office sent it and sent back unchanged (never a clock of the store); the same answer applied
 * twice changes nothing; an unreachable or refusing head office changes nothing; pages, failures while applying or
 * saving the cursor, the exchange log (DOWN, one row per pull that brought something or failed). Real HeadOfficeClient
 * over MockRestServiceServer (an unexpected request fails the test), a recording handler, in-memory hol_ tables.
 */
class CopiesDownPullerTest {

	private static final String DOWN = "http://localhost:888/zsretail/api/ho/down/promotions";

	private final ObjectMapper mapper = new ObjectMapper();

	/** The handler's records: what the store has, by code. */
	private final Map<String, JsonNode> store = new LinkedHashMap<>();
	private int applyCalls;
	private int retryCalls;
	private RuntimeException applyFailure;
	private DownApplyResult retryResult = DownApplyResult.none();
	private int localToDeactivate;

	/** hol_down_cursor */
	private final Map<DataDomain, DownCursor> cursors = new LinkedHashMap<>();
	private boolean cursorSaveFails;

	/** hol_exchange_log */
	private final List<LinkExchange> exchanges = new ArrayList<>();

	private MockRestServiceServer server;
	private CopiesDownJob job;

	@BeforeEach
	void setUp() {
		store.clear();
		cursors.clear();
		exchanges.clear();
		applyCalls = 0;
		retryCalls = 0;
		applyFailure = null;
		cursorSaveFails = false;
		localToDeactivate = 0;
		retryResult = DownApplyResult.none();
		job = job(Collections.singletonList(handler()));
	}

	private CopiesDownJob job(List<DownHandler> handlers) {
		RestTemplate restTemplate = new RestTemplate();
		server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(false).build();
		GeneralSetupService generalSetup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "RS01";
			}
		};
		HeadOfficeClient client = new HeadOfficeClient(restTemplate, generalSetup, "http://localhost:888/zsretail/api",
				"AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde", "2.1.0");
		CopiesDownPuller puller = new CopiesDownPuller(client, handlers, cursorRepository(), exchangeLog(),
				TransactionOperations.withoutTransaction());
		return new CopiesDownJob(puller, 60);
	}

	// ─── Head office answers ──────────────────────────────────────

	private ObjectNode record(String code, String name) {
		return mapper.createObjectNode().put("code", code).put("name", name);
	}

	private ResponseCreator page(String cursor, boolean more, List<ObjectNode> records, String... removed) {
		ObjectNode body = mapper.createObjectNode();
		body.put("domain", "PROMOTIONS");
		ArrayNode array = body.putArray("records");
		records.forEach(array::add);
		ArrayNode removedArray = body.putArray("removed");
		Arrays.stream(removed).forEach(removedArray::add);
		body.put("cursor", cursor);
		body.put("more", more);
		return withSuccess(body.toString(), MediaType.APPLICATION_JSON);
	}

	private void expect(String cursor, ResponseCreator response) {
		server.expect(requestTo(startsWith(DOWN + "?"))).andExpect(method(HttpMethod.GET))
				.andExpect(request -> assertEquals(cursor, UriUtils.decode(UriComponentsBuilder.fromUri(request.getURI()).build()
						.getQueryParams().getFirst("cursor"), StandardCharsets.UTF_8), "the cursor read back by the head office"))
				.andExpect(request -> assertFalse(request.getURI().getRawQuery().contains("+"), "encoded strictly"))
				.andExpect(queryParam("limit", "100"))
				.andExpect(header("X-Store-Code", "RS01"))
				.andExpect(header("X-Store-Key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde"))
				.andRespond(response);
	}

	private String savedCursor() {
		DownCursor cursor = cursors.get(DataDomain.PROMOTIONS);
		return cursor == null ? null : cursor.getCursorValue();
	}

	// ─── Tests ────────────────────────────────────────────────────

	@Test
	@DisplayName("The cursor is saved exactly as the head office sent it and sent back unchanged at the next pull")
	void cursorSentBackUnchanged() {
		expect("", page("41", false, Arrays.asList(record("P1", "Summer"), record("P2", "Winter"))));
		LinkJobRun run = job.run();
		server.verify();
		assertEquals("41", savedCursor());
		assertEquals(LinkJobResult.SUCCESS, run.getResult());
		assertEquals("PROMOTIONS: 2 applied, 0 unchanged, 0 removed, 0 waiting, 0 in error", run.getMessage());
		assertFalse(run.isRunAgainSoon());
		assertEquals(1, exchanges.size());
		LinkExchange row = exchanges.get(0);
		assertEquals(CopiesDownJob.CODE, row.getJob());
		assertEquals(ExchangeDirection.DOWN, row.getDirection());
		assertEquals(2, row.getRecordCount());
		assertEquals(LinkJobResult.SUCCESS, row.getResult());
		assertNull(row.getError());

		setUp(job);
		expect("41", page("v2:opaque/+=", false, Collections.emptyList()));
		run = job.run();
		server.verify();
		assertEquals("v2:opaque/+=", savedCursor(), "the store never makes or changes a cursor");
		assertEquals("PROMOTIONS: nothing new", run.getMessage());
		assertTrue(exchanges.isEmpty(), "a pull with nothing new writes no row");

		setUp(job);
		expect("v2:opaque/+=", page("v2:opaque/+=", false, Collections.emptyList()));
		job.run();
		server.verify();
	}

	@Test
	@DisplayName("The same answer applied twice changes nothing; a cursor that could not be saved brings the same page again")
	void sameAnswerTwice() {
		expect("", page("7", false, Arrays.asList(record("P1", "Summer")), "OLD"));
		job.run();
		server.verify();
		Map<String, JsonNode> after = new LinkedHashMap<>(store);

		setUp(job);
		cursors.clear(); // the head office answers the same page again (e.g. the store's database was restored)
		expect("", page("7", false, Arrays.asList(record("P1", "Summer")), "OLD"));
		LinkJobRun run = job.run();
		server.verify();
		assertEquals(after, store);
		assertEquals("PROMOTIONS: 0 applied, 1 unchanged, 0 removed, 0 waiting, 0 in error", run.getMessage());
		assertEquals("7", savedCursor());

		setUp(job);
		cursorSaveFails = true;
		expect("7", page("8", false, Arrays.asList(record("P1", "Summer v2"))));
		run = job.run();
		server.verify();
		assertEquals(LinkJobResult.ERROR, run.getResult());
		assertTrue(run.getMessage().startsWith("PROMOTIONS: cursor not saved, the page is pulled again"), run.getMessage());
		assertEquals("7", savedCursor());

		setUp(job);
		cursorSaveFails = false;
		expect("7", page("8", false, Arrays.asList(record("P1", "Summer v2"))));
		run = job.run();
		server.verify();
		assertEquals("PROMOTIONS: 0 applied, 1 unchanged, 0 removed, 0 waiting, 0 in error", run.getMessage());
		assertEquals("8", savedCursor());
		assertEquals("Summer v2", store.get("P1").get("name").asText());
	}

	@Test
	@DisplayName("Head office unreachable, refusing or answering something unreadable: nothing changes, one ERROR row")
	void notDelivered() {
		store.put("P1", record("P1", "Summer"));
		cursors.put(DataDomain.PROMOTIONS, cursor("5"));
		Map<String, JsonNode> before = new LinkedHashMap<>(store);
		ResponseCreator[] failures = { withStatus(HttpStatus.UNAUTHORIZED), withStatus(HttpStatus.PAYMENT_REQUIRED),
				withStatus(HttpStatus.SERVICE_UNAVAILABLE), request -> {
					throw new ConnectException("Connection refused");
				}, withSuccess("<html>proxy</html>", MediaType.TEXT_HTML),
				withSuccess("{\"domain\":\"LOYALTY\",\"records\":[],\"removed\":[],\"cursor\":\"9\"}",
						MediaType.APPLICATION_JSON),
				withSuccess("{\"domain\":\"PROMOTIONS\",\"records\":[{\"code\":\"P1\"}],\"removed\":[]}",
						MediaType.APPLICATION_JSON) };
		String[] states = { "REFUSED", "REFUSED", "ERROR", "OFFLINE", "ERROR", "ERROR", "ERROR" };
		for (int i = 0; i < failures.length; i++) {
			setUp(job);
			expect("5", failures[i]);
			LinkJobRun run = job.run();
			server.verify();
			assertEquals(LinkJobResult.ERROR, run.getResult(), states[i]);
			assertTrue(run.getMessage().startsWith(
					"PROMOTIONS: not delivered, the store keeps its last copy (" + states[i] + ": "), run.getMessage());
			assertEquals(0, applyCalls, "nothing applied");
			assertEquals(before, store);
			assertEquals("5", savedCursor(), "cursor unchanged");
			assertEquals(1, exchanges.size());
			assertEquals(LinkJobResult.ERROR, exchanges.get(0).getResult());
			assertEquals(0, exchanges.get(0).getRecordCount());
			assertTrue(exchanges.get(0).getError().startsWith("PROMOTIONS: " + states[i] + ": "));
			assertEquals(1, retryCalls, "the retries do not wait for the head office");
		}
	}

	@Test
	@DisplayName("more: the next page in the same cycle with the new cursor; after 10 pages the next cycle comes soon")
	void pages() {
		expect("", page("1", true, Arrays.asList(record("P1", "a"))));
		expect("1", page("2", false, Arrays.asList(record("P2", "b")), "P0"));
		LinkJobRun run = job.run();
		server.verify();
		assertEquals("2", savedCursor());
		assertFalse(run.isRunAgainSoon());
		assertEquals(2, exchanges.size(), "one row per pull that brought something");
		assertEquals(2, exchanges.get(1).getRecordCount(), "records and removed codes");

		setUp(job);
		String cursor = "2";
		for (int i = 0; i < CopiesDownPuller.MAX_PAGES; i++) {
			String next = String.valueOf(10 + i);
			expect(cursor, page(next, true, Collections.emptyList()));
			cursor = next;
		}
		run = job.run();
		server.verify();
		assertTrue(run.isRunAgainSoon());
		assertEquals("19", savedCursor());
	}

	@Test
	@DisplayName("A page the store cannot apply: the cursor does not move, one ERROR row, the page comes again")
	void applyFails() {
		cursors.put(DataDomain.PROMOTIONS, cursor("5"));
		applyFailure = new IllegalStateException("database down");
		expect("5", page("6", false, Arrays.asList(record("P1", "a"))));
		LinkJobRun run = job.run();
		server.verify();
		assertEquals(LinkJobResult.ERROR, run.getResult());
		assertEquals("PROMOTIONS: page not applied, pulled again at the next cycle (IllegalStateException: database down)",
				run.getMessage());
		assertEquals("5", savedCursor());
		assertEquals(1, exchanges.size());
		assertEquals(LinkJobResult.ERROR, exchanges.get(0).getResult());
		assertEquals(1, exchanges.get(0).getRecordCount());
	}

	@Test
	@DisplayName("Problems reported by the handler: WARNING run and row with the first problem; retries counted in the run")
	void problems() {
		expect("", page("3", false, Arrays.asList(record("BAD", "clash"), record("P1", "a"))));
		LinkJobRun run = job.run();
		server.verify();
		assertEquals(LinkJobResult.WARNING, run.getResult());
		assertEquals(LinkJobResult.WARNING, exchanges.get(0).getResult());
		assertEquals("PROMOTIONS: BAD: code already used by a local promotion", exchanges.get(0).getError());

		setUp(job);
		retryResult = DownApplyResult.none();
		retryResult.addWaiting("P9", "item I9 is not in this store");
		expect("3", page("3", false, Collections.emptyList()));
		run = job.run();
		server.verify();
		assertEquals(LinkJobResult.WARNING, run.getResult());
		assertEquals("PROMOTIONS: 0 applied, 0 unchanged, 0 removed, 1 waiting, 0 in error", run.getMessage());
		assertTrue(exchanges.isEmpty(), "a retry is not a pull: no row");
	}

	@Test
	@DisplayName("Local records set inactive before the pull: one WARNING row with how many, none when there is nothing to do")
	void localDeactivated() {
		localToDeactivate = 3;
		expect("", page("1", false, Collections.emptyList()));
		LinkJobRun run = job.run();
		server.verify();
		assertEquals("PROMOTIONS: 3 local set inactive; nothing new", run.getMessage());
		assertEquals(1, exchanges.size());
		assertEquals(LinkJobResult.WARNING, exchanges.get(0).getResult());
		assertEquals(3, exchanges.get(0).getRecordCount());
		assertEquals("PROMOTIONS: 3 local records set inactive: the domain is owned by the head office",
				exchanges.get(0).getError());

		setUp(job);
		expect("1", page("1", false, Collections.emptyList()));
		run = job.run();
		server.verify();
		assertEquals("PROMOTIONS: nothing new", run.getMessage());
		assertTrue(exchanges.isEmpty());
	}

	@Test
	@DisplayName("Job: code COPIES_DOWN, first run 25 s after the start, default frequency; no handler; a cycle that throws")
	void jobBasics() {
		assertEquals("COPIES_DOWN", job.getCode());
		assertEquals(25, job.getFirstDelay().getSeconds());
		assertEquals(60, job.getDefaultIntervalSeconds());
		assertEquals(90, new CopiesDownJob(null, 90).getDefaultIntervalSeconds());

		LinkJobRun none = job(Collections.emptyList()).run();
		assertEquals(LinkJobResult.SUCCESS, none.getResult());
		assertEquals("no domain to pull", none.getMessage());

		CopiesDownPuller failing = new CopiesDownPuller(null, Collections.emptyList(), null, null,
				TransactionOperations.withoutTransaction()) {
			@Override
			public Cycle runCycle() {
				throw new IllegalStateException("boom");
			}
		};
		LinkJobRun run = new CopiesDownJob(failing, 60).run();
		assertEquals(LinkJobResult.ERROR, run.getResult());
		assertEquals("cycle failed (IllegalStateException: boom)", run.getMessage());
	}

	// ─── Stubs ────────────────────────────────────────────────────

	/** A new mock server on a job that keeps the in-memory tables; the exchange rows of the previous run are cleared. */
	private void setUp(CopiesDownJob unused) {
		exchanges.clear();
		applyCalls = 0;
		retryCalls = 0;
		job = job(Collections.singletonList(handler()));
	}

	/** Applies by code: a record equal to the stored one is unchanged; code BAD is refused; removed codes deleted. */
	private DownHandler handler() {
		return new DownHandler() {
			@Override
			public DataDomain getDomain() {
				return DataDomain.PROMOTIONS;
			}

			@Override
			public DownApplyResult apply(List<JsonNode> records, List<String> removed) {
				applyCalls++;
				if (applyFailure != null) {
					throw applyFailure;
				}
				DownApplyResult result = DownApplyResult.none();
				for (JsonNode record : records) {
					String code = record.get("code").asText();
					if ("BAD".equals(code)) {
						result.addError(code, "code already used by a local promotion");
					} else if (record.equals(store.get(code))) {
						result.addUnchanged();
					} else {
						store.put(code, record);
						result.addApplied();
					}
				}
				for (String code : removed) {
					if (store.remove(code) != null) {
						result.addRemoved();
					}
				}
				return result;
			}

			@Override
			public int deactivateLocal() {
				int count = localToDeactivate;
				localToDeactivate = 0;
				return count;
			}

			@Override
			public DownApplyResult retry() {
				retryCalls++;
				return retryResult;
			}
		};
	}

	private static DownCursor cursor(String value) {
		DownCursor cursor = new DownCursor();
		cursor.setDomain(DataDomain.PROMOTIONS);
		cursor.setCursorValue(value);
		return cursor;
	}

	private DownCursorRepository cursorRepository() {
		return (DownCursorRepository) Proxy.newProxyInstance(DownCursorRepository.class.getClassLoader(),
				new Class<?>[] { DownCursorRepository.class }, (proxy, method, args) -> {
					switch (method.getName()) {
						case "findByDomain":
							DownCursor found = cursors.get(args[0]);
							return Optional.ofNullable(found == null ? null : cursor(found.getCursorValue()));
						case "save":
							if (cursorSaveFails) {
								throw new IllegalStateException("deadlock");
							}
							DownCursor saved = (DownCursor) args[0];
							cursors.put(saved.getDomain(), saved);
							return saved;
						default:
							throw new UnsupportedOperationException(method.getName());
					}
				});
	}

	private LinkExchangeLog exchangeLog() {
		LinkExchangeRepository repository = (LinkExchangeRepository) Proxy.newProxyInstance(
				LinkExchangeRepository.class.getClassLoader(), new Class<?>[] { LinkExchangeRepository.class },
				(proxy, method, args) -> {
					if ("save".equals(method.getName())) {
						exchanges.add((LinkExchange) args[0]);
						return args[0];
					}
					throw new UnsupportedOperationException(method.getName());
				});
		return new LinkExchangeLog(repository, TransactionOperations.withoutTransaction(), 30);
	}

}
