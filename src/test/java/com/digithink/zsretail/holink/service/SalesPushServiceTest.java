package com.digithink.zsretail.holink.service;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.headoffice.dto.ReturnCopyDTO;
import com.digithink.zsretail.headoffice.dto.SessionCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketLineCopyDTO;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.holink.repository.LinkExchangeRepository;
import com.digithink.zsretail.holink.scheduler.SalesPushJob;
import com.digithink.zsretail.holink.dto.SalesDocumentRef;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.holink.model.SalesCopy;
import com.digithink.zsretail.holink.model.SalesCopyCursor;
import com.digithink.zsretail.holink.repository.SalesCopyCursorRepository;
import com.digithink.zsretail.holink.repository.SalesCopyRepository;
import com.digithink.zsretail.service.GeneralSetupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, task 2.4: the push job. A rejected document is retried and sent at a later cycle; an unreachable
 * head office (or a refused key, or an unexpected answer) changes nothing in the tracking rows; one rejected document
 * in a batch does not stop the others. Also: a copy the head office already has is not sent again, a document the
 * store cannot build becomes ERROR, batches are bounded and oldest first. Real HeadOfficeClient over
 * MockRestServiceServer (an unexpected request fails the test), in-memory hol_ tables, no Spring context.
 */
class SalesPushServiceTest {

	private static final String SALES = "http://localhost:888/zsretail/api/ho/sales/";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 12, 0);

	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

	/** hol_sales_copy, by id. */
	private final Map<Long, SalesCopy> table = new LinkedHashMap<>();

	/** The store's documents as copies, by type and local id; a RuntimeException value is thrown when loaded. */
	private final Map<String, Object> documents = new HashMap<>();

	/** Store clock: the next values, then the last one again. */
	private final Deque<LocalDateTime> clock = new ArrayDeque<>();
	private LocalDateTime lastTime = NOW;

	/** Paths and document numbers of the requests, in order. */
	private final List<String> requests = new ArrayList<>();

	private MockRestServiceServer server;
	private int batchSize = 50;
	private boolean searchFails;

	@BeforeEach
	void setUp() {
		table.clear();
		documents.clear();
		requests.clear();
	}

	private SalesPushService service() {
		RestTemplate restTemplate = new RestTemplate();
		server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(false).build();
		GeneralSetupService generalSetup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "SHOWROOM-S";
			}
		};
		HeadOfficeClient client = new HeadOfficeClient(restTemplate, generalSetup, "http://localhost:888/zsretail/api",
				"AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde", "1.12.0");
		SalesDocumentSource source = new SalesDocumentSource() {
			@Override
			public List<SalesDocumentRef> findChanged(SalesCopyType type, LocalDateTime from,
					LocalDateTime afterChangedAt, long afterId, LocalDateTime until, int limit) {
				if (searchFails) {
					throw new IllegalStateException("The query has timed out.");
				}
				return new ArrayList<>(); // the search finds nothing new: the rows below are the queue
			}

			@Override
			public Object loadCopy(SalesCopyType type, Long localId) {
				Object copy = documents.get(type + ":" + localId);
				if (copy instanceof RuntimeException) {
					throw (RuntimeException) copy;
				}
				return copy;
			}
		};
		SalesCopyRepository copies = stub(SalesCopyRepository.class, (method, args) -> {
			switch (method) {
				case "findQueue":
					Collection<?> statuses = (Collection<?>) args[1];
					return table.values().stream()
							.filter(c -> c.getDocumentType() == args[0] && statuses.contains(c.getStatus()))
							.sorted(Comparator.comparing(SalesCopy::getAttempts)
									.thenComparing(SalesCopy::getDocumentDate)
									.thenComparing(SalesCopy::getId))
							.limit(((Pageable) args[2]).getPageSize())
							.map(SalesPushServiceTest::detached)
							.collect(Collectors.toList());
				case "saveAll":
					for (Object o : (Iterable<?>) args[0]) {
						SalesCopy row = (SalesCopy) o;
						table.put(row.getId(), detached(row));
					}
					return args[0];
				case "findByDocumentTypeAndLocalIdIn":
					return new ArrayList<>();
				case "countByStatus":
					Map<SalesCopyStatus, Long> counts = table.values().stream()
							.collect(Collectors.groupingBy(SalesCopy::getStatus, Collectors.counting()));
					return counts.entrySet().stream().map(e -> new Object[] { e.getKey(), e.getValue() })
							.collect(Collectors.toList());
				default:
					return UNHANDLED;
			}
		});
		SalesCopyCursorRepository cursors = stub(SalesCopyCursorRepository.class, (method, args) -> {
			switch (method) {
				case "findByDocumentType":
					return Optional.empty();
				case "save":
					return (SalesCopyCursor) args[0];
				default:
					return UNHANDLED;
			}
		});
		SalesPushSettings settings = new SalesPushSettings("", batchSize, 60);
		SalesCopyFinder finder = new SalesCopyFinder(source, copies, cursors, settings,
				TransactionOperations.withoutTransaction());
		return new SalesPushService(finder, source, copies, client, settings, TransactionOperations.withoutTransaction(),
				TransactionOperations.withoutTransaction(), () -> {
					if (!clock.isEmpty()) {
						lastTime = clock.poll();
					}
					return lastTime;
				});
	}

	/** A copy of the row, as JPA gives a detached entity: the table changes only through saveAll. */
	private static SalesCopy detached(SalesCopy row) {
		SalesCopy copy = new SalesCopy();
		copy.setId(row.getId());
		copy.setDocumentType(row.getDocumentType());
		copy.setLocalId(row.getLocalId());
		copy.setDocumentNumber(row.getDocumentNumber());
		copy.setDocumentDate(row.getDocumentDate());
		copy.setStatus(row.getStatus());
		copy.setAttempts(row.getAttempts());
		copy.setLastError(row.getLastError());
		copy.setLastPushDate(row.getLastPushDate());
		copy.setContentHash(row.getContentHash());
		return copy;
	}

	// --- Rows and documents ---

	private SalesCopy row(SalesCopyType type, long localId, String number, LocalDateTime date) {
		SalesCopy row = new SalesCopy();
		row.setId((long) table.size() + 1);
		row.setDocumentType(type);
		row.setLocalId(localId);
		row.setDocumentNumber(number);
		row.setDocumentDate(date);
		row.setStatus(SalesCopyStatus.PENDING);
		table.put(row.getId(), row);
		return row;
	}

	private TicketCopyDTO ticket(long localId, String number, LocalDateTime date) {
		row(SalesCopyType.TICKET, localId, number, date);
		TicketCopyDTO copy = new TicketCopyDTO();
		copy.setSalesNumber(number);
		copy.setSalesDate(date);
		copy.setStatus("COMPLETED");
		TicketLineCopyDTO line = new TicketLineCopyDTO();
		line.setLineNo(1);
		line.setItemCode("ITM-100");
		line.setQuantity(1);
		copy.getLines().add(line);
		documents.put("TICKET:" + localId, copy);
		return copy;
	}

	private SalesCopy stored(String number) {
		return table.values().stream().filter(r -> r.getDocumentNumber().equals(number)).findFirst().get();
	}

	/** Every row as text: compared before and after a cycle. */
	private List<String> snapshot() {
		return table.values().stream().map(SalesCopy::toString).collect(Collectors.toList());
	}

	// --- Head office answers ---

	/** Answers each document of the batch: rejected when its number is in {@code rejections}, accepted otherwise. */
	private ResponseCreator answering(Map<String, String> rejections) {
		return request -> {
			String path = request.getURI().getPath();
			JsonNode batch = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
			StringBuilder results = new StringBuilder();
			for (JsonNode copy : batch) {
				String number = copy.has("salesNumber") ? copy.get("salesNumber").asText()
						: copy.has("returnNumber") ? copy.get("returnNumber").asText() : copy.get("sessionNumber").asText();
				requests.add(path.substring(path.lastIndexOf('/') + 1) + ":" + number);
				if (results.length() > 0) {
					results.append(',');
				}
				String reason = rejections.get(number);
				results.append("{\"documentNumber\":\"").append(number).append("\",\"accepted\":").append(reason == null)
						.append(",\"message\":").append(reason == null ? "null" : "\"" + reason + "\"").append('}');
			}
			return withSuccess("{\"results\":[" + results + "]}", MediaType.APPLICATION_JSON).createResponse(request);
		};
	}

	private ResponseCreator acceptingAll() {
		return answering(new HashMap<>());
	}

	private static Map<String, String> rejecting(String number, String reason) {
		Map<String, String> rejections = new HashMap<>();
		rejections.put(number, reason);
		return rejections;
	}

	// --- L1 of the task ---

	@Test
	@DisplayName("Retry after a rejection: ERROR with the reason and one attempt, then SENT at the next cycle; then nothing is sent")
	void retryAfterRejection() {
		TicketCopyDTO copy = ticket(1, "T-1", NOW.minusDays(1));
		SalesPushService service = service();
		server.expect(requestTo(SALES + "tickets"))
				.andRespond(answering(rejecting("T-1", "line 1: itemCode is required")));

		SalesPushService.Cycle first = service.runCycle();

		server.verify();
		SalesCopy rejected = stored("T-1");
		assertEquals(SalesCopyStatus.ERROR, rejected.getStatus());
		assertEquals(1, rejected.getAttempts());
		assertEquals("line 1: itemCode is required", rejected.getLastError());
		assertEquals(NOW, rejected.getLastPushDate());
		assertNull(rejected.getContentHash());
		assertEquals(1, first.getRejected());
		assertEquals(HeadOfficeLinkState.ONLINE, first.getState());

		server.reset();
		server.expect(requestTo(SALES + "tickets")).andRespond(acceptingAll());
		clock.add(NOW.plusMinutes(1));
		SalesPushService.Cycle second = service.runCycle();

		server.verify();
		SalesCopy sent = stored("T-1");
		assertEquals(SalesCopyStatus.SENT, sent.getStatus());
		assertEquals(2, sent.getAttempts());
		assertNull(sent.getLastError());
		assertEquals(NOW.plusMinutes(1), sent.getLastPushDate());
		assertEquals(SalesPushService.hash(copy), sent.getContentHash());
		assertEquals(1, second.getSent());

		server.reset();
		assertTrue(service.runCycle().isIdle(), "nothing left: no request");
		server.verify();
	}

	@Test
	@DisplayName("Head office unreachable, key refused, unexpected answer: the tracking rows do not change at all, the cycle stops")
	void notDeliveredChangesNothing() {
		ticket(1, "T-1", NOW.minusDays(3));
		ticket(2, "T-2", NOW.minusDays(2));
		SalesCopy rejectedBefore = stored("T-2");
		rejectedBefore.setStatus(SalesCopyStatus.ERROR);
		rejectedBefore.setAttempts(2);
		rejectedBefore.setLastError("old reason");
		rejectedBefore.setLastPushDate(NOW.minusHours(1));
		ticket(3, "T-3", NOW.minusDays(1)).setTotalAmount(99.0);
		stored("T-3").setContentHash("hash-of-the-copy-accepted-before-its-change");
		row(SalesCopyType.RETURN, 4, "R-4", NOW.minusDays(1));
		documents.put("RETURN:4", new ReturnCopyDTO());
		List<String> before = snapshot();

		ResponseCreator[] failures = { request -> {
			throw new ConnectException("Connection refused");
		}, withStatus(HttpStatus.UNAUTHORIZED), withStatus(HttpStatus.PAYMENT_REQUIRED),
				withStatus(HttpStatus.SERVICE_UNAVAILABLE), withSuccess("<html>proxy</html>", MediaType.TEXT_HTML) };
		HeadOfficeLinkState[] states = { HeadOfficeLinkState.OFFLINE, HeadOfficeLinkState.REFUSED,
				HeadOfficeLinkState.REFUSED, HeadOfficeLinkState.ERROR, HeadOfficeLinkState.ERROR };
		SalesPushService service = service();
		for (int i = 0; i < failures.length; i++) {
			server.reset();
			// one request only: the cycle stops, the returns are not tried
			server.expect(ExpectedCount.once(), requestTo(SALES + "tickets")).andRespond(failures[i]);

			SalesPushService.Cycle cycle = service.runCycle();

			server.verify();
			assertEquals(before, snapshot(), "rows unchanged after " + states[i]);
			assertEquals(states[i], cycle.getState());
			assertTrue(cycle.isStopped());
			assertFalse(cycle.isMore(), "no fast retry while the head office does not answer");
			assertEquals(0, cycle.getSent() + cycle.getRejected() + cycle.getNotBuilt());
		}
	}

	@Test
	@DisplayName("One rejected document in a batch does not stop the others; a document missing from the answer is ERROR too")
	void oneRejectedDoesNotStopOthers() {
		ticket(1, "T-1", NOW.minusDays(4));
		ticket(2, "T-2", NOW.minusDays(3));
		ticket(3, "T-3", NOW.minusDays(2));
		ticket(4, "T-4", NOW.minusDays(1));
		SalesPushService service = service();
		server.expect(requestTo(SALES + "tickets")).andRespond(request -> {
			String body = "{\"results\":[{\"documentNumber\":\"T-1\",\"accepted\":true},"
					+ "{\"documentNumber\":\"T-2\",\"accepted\":false,\"message\":\"payment 1: paymentMethodCode is required\"},"
					+ "{\"documentNumber\":\"T-3\",\"accepted\":true}]}";
			return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
		});

		SalesPushService.Cycle cycle = service.runCycle();

		server.verify();
		assertEquals(SalesCopyStatus.SENT, stored("T-1").getStatus());
		assertEquals(SalesCopyStatus.ERROR, stored("T-2").getStatus());
		assertEquals("payment 1: paymentMethodCode is required", stored("T-2").getLastError());
		assertEquals(SalesCopyStatus.SENT, stored("T-3").getStatus());
		assertEquals(SalesCopyStatus.ERROR, stored("T-4").getStatus(), "not resent in the same cycle");
		assertEquals("no result from the head office for this document", stored("T-4").getLastError());
		assertEquals(1, stored("T-4").getAttempts());
		assertEquals(2, cycle.getSent());
		assertEquals(2, cycle.getRejected());
	}

	// --- Content check and local failures ---

	@Test
	@DisplayName("A copy equal to the one accepted last is marked SENT without being sent; a changed one is sent")
	void unchangedCopyIsNotSent() {
		TicketCopyDTO same = ticket(1, "T-1", NOW.minusDays(2));
		stored("T-1").setContentHash(SalesPushService.hash(same));
		stored("T-1").setAttempts(0);
		TicketCopyDTO changed = ticket(2, "T-2", NOW.minusDays(1));
		stored("T-2").setContentHash(SalesPushService.hash(changed));
		changed.getLines().get(0).setQuantity(2); // changed after it was accepted
		SalesPushService service = service();
		server.expect(requestTo(SALES + "tickets")).andRespond(acceptingAll());

		SalesPushService.Cycle cycle = service.runCycle();

		server.verify();
		assertEquals(Arrays.asList("tickets:T-2"), requests);
		assertEquals(SalesCopyStatus.SENT, stored("T-1").getStatus());
		assertEquals(0, stored("T-1").getAttempts(), "not sent, so no attempt");
		assertEquals(1, cycle.getUnchanged());
		assertEquals(SalesPushService.hash(changed), stored("T-2").getContentHash());
	}

	@Test
	@DisplayName("A document the store cannot build (deleted, read failure) becomes ERROR with an attempt; the others are sent")
	void documentNotBuilt() {
		ticket(1, "T-1", NOW.minusDays(3));
		documents.remove("TICKET:1");
		ticket(2, "T-2", NOW.minusDays(2));
		documents.put("TICKET:2", new IllegalStateException("The query has timed out."));
		ticket(3, "T-3", NOW.minusDays(1));
		SalesPushService service = service();
		server.expect(requestTo(SALES + "tickets")).andRespond(acceptingAll());

		SalesPushService.Cycle cycle = service.runCycle();

		server.verify();
		assertEquals(Arrays.asList("tickets:T-3"), requests);
		assertEquals(SalesCopyStatus.ERROR, stored("T-1").getStatus());
		assertEquals("the document no longer exists in the store", stored("T-1").getLastError());
		assertEquals(1, stored("T-1").getAttempts());
		assertEquals(SalesCopyStatus.ERROR, stored("T-2").getStatus());
		assertEquals("the store could not build the copy (IllegalStateException: The query has timed out.)",
				stored("T-2").getLastError());
		assertEquals(SalesCopyStatus.SENT, stored("T-3").getStatus());
		assertEquals(2, cycle.getNotBuilt());
	}

	// --- Bounds and order ---

	@Test
	@DisplayName("Batches of batch-size, oldest first, never tried before rejected; 2 batches per type per cycle, types in turn")
	void batchesAndOrder() {
		batchSize = 2;
		for (int i = 5; i >= 1; i--) {
			ticket(i, "T-" + i, NOW.minusDays(10 - i)); // T-1 is the oldest
		}
		ticket(9, "T-9", NOW.minusDays(30));
		stored("T-9").setStatus(SalesCopyStatus.ERROR);
		stored("T-9").setAttempts(3);
		row(SalesCopyType.RETURN, 20, "R-20", NOW.minusDays(1));
		documents.put("RETURN:20", returnCopy("R-20"));
		row(SalesCopyType.SESSION, 30, "S-30", NOW.minusDays(1));
		SessionCopyDTO session = new SessionCopyDTO();
		session.setSessionNumber("S-30");
		documents.put("SESSION:30", session);
		SalesPushService service = service();
		server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(SALES)))
				.andRespond(acceptingAll());

		SalesPushService.Cycle first = service.runCycle();

		assertEquals(Arrays.asList("tickets:T-1", "tickets:T-2", "returns:R-20", "sessions:S-30", "tickets:T-3",
				"tickets:T-4"), requests);
		assertEquals(6, first.getSent());
		assertTrue(first.isMore(), "a full batch with documents never tried: the next cycle comes sooner");
		assertEquals(SalesCopyStatus.PENDING, stored("T-5").getStatus());
		assertEquals(SalesCopyStatus.ERROR, stored("T-9").getStatus(), "the rejected one waits for the others");

		requests.clear();
		service.runCycle();
		assertEquals(Arrays.asList("tickets:T-5", "tickets:T-9"), requests);
		assertEquals(SalesCopyStatus.SENT, stored("T-9").getStatus());

		requests.clear();
		SalesPushService.Cycle third = service.runCycle();
		assertTrue(requests.isEmpty());
		assertFalse(third.isMore(), "caught up: back to the usual interval");
		assertTrue(third.isIdle());
	}

	@Test
	@DisplayName("A batch of rejected documents rejected again does not speed up the next cycle")
	void rejectedOnlyIsNotCatchUp() {
		batchSize = 2;
		ticket(1, "T-1", NOW.minusDays(2));
		ticket(2, "T-2", NOW.minusDays(1));
		for (String number : new String[] { "T-1", "T-2" }) {
			stored(number).setStatus(SalesCopyStatus.ERROR);
			stored(number).setAttempts(1);
		}
		SalesPushService service = service();
		Map<String, String> rejections = rejecting("T-1", "bad");
		rejections.put("T-2", "bad");
		server.expect(requestTo(SALES + "tickets")).andRespond(answering(rejections));

		SalesPushService.Cycle cycle = service.runCycle();

		server.verify();
		assertEquals(2, cycle.getRejected());
		assertEquals(2, stored("T-1").getAttempts());
		assertFalse(cycle.isMore());
	}

	@Test
	@DisplayName("No new batch once the cycle has run 20 s: the heartbeat is not held back; the next cycle comes sooner")
	void cycleBudget() {
		batchSize = 1;
		ticket(1, "T-1", NOW.minusDays(2));
		ticket(2, "T-2", NOW.minusDays(1));
		SalesPushService service = service();
		server.expect(ExpectedCount.once(), requestTo(SALES + "tickets")).andRespond(acceptingAll());
		// start, check before the first batch, time of the first batch, check before the next one
		clock.add(NOW);
		clock.add(NOW);
		clock.add(NOW);
		clock.add(NOW.plusSeconds(20));

		SalesPushService.Cycle cycle = service.runCycle();

		server.verify();
		assertEquals(1, cycle.getSent());
		assertTrue(cycle.isBudgetReached());
		assertTrue(cycle.isMore());
		assertEquals(SalesCopyStatus.PENDING, stored("T-2").getStatus());
	}

	// --- Hash and counts ---

	@Test
	@DisplayName("The hash depends on the content only: equal after a JSON round trip, different when a line changes")
	void hash() throws Exception {
		TicketCopyDTO copy = ticket(1, "T-1", NOW);
		TicketCopyDTO readBack = mapper.readValue(mapper.writeValueAsString(copy), TicketCopyDTO.class);
		assertEquals(SalesPushService.hash(copy), SalesPushService.hash(readBack));
		assertEquals(64, SalesPushService.hash(copy).length());
		readBack.getLines().get(0).setQuantity(5);
		assertNotEquals(SalesPushService.hash(copy), SalesPushService.hash(readBack));
	}

	@Test
	@DisplayName("Counts: every status, 0 when it has no row")
	void counts() {
		ticket(1, "T-1", NOW);
		ticket(2, "T-2", NOW);
		stored("T-2").setStatus(SalesCopyStatus.SENT);
		Map<SalesCopyStatus, Long> counts = service().counts();
		assertEquals(Long.valueOf(1), counts.get(SalesCopyStatus.PENDING));
		assertEquals(Long.valueOf(1), counts.get(SalesCopyStatus.SENT));
		assertEquals(Long.valueOf(0), counts.get(SalesCopyStatus.ERROR));
	}

	private static ReturnCopyDTO returnCopy(String number) {
		ReturnCopyDTO copy = new ReturnCopyDTO();
		copy.setReturnNumber(number);
		return copy;
	}

	// --- Task 2.6: the job and its exchange log rows ---

	private final List<LinkExchange> logRows = new ArrayList<>();
	private boolean logDown;

	/** The push as a job of the head office link, over an in-memory exchange log (or one that cannot write). */
	private SalesPushJob job() {
		SalesPushService service = service();
		LinkExchangeRepository repository = stub(LinkExchangeRepository.class, (method, args) -> {
			if (logDown) {
				throw new IllegalStateException("hol_exchange_log unavailable");
			}
			if ("save".equals(method)) {
				logRows.add((LinkExchange) args[0]);
				return args[0];
			}
			return "deleteOlderThan".equals(method) ? 0 : UNHANDLED;
		});
		return new SalesPushJob(service, new SalesPushSettings("", batchSize, 60),
				new LinkExchangeLog(repository, TransactionOperations.withoutTransaction(), 30));
	}

	@Test
	@DisplayName("Task 2.6: a cycle with nothing to send writes no exchange row; the job says 'nothing to send'")
	void emptyCycleNoRow() {
		SalesPushJob job = job();

		LinkJobRun run = job.run();

		server.verify(); // no request
		assertTrue(logRows.isEmpty());
		assertEquals(LinkJobResult.SUCCESS, run.getResult());
		assertEquals("nothing to send", run.getMessage());
	}

	@Test
	@DisplayName("Task 2.6: one row per batch that sent something: records, result (WARNING with a rejection), first problem, duration")
	void batchRows() {
		batchSize = 2;
		ticket(1, "T-1", NOW.minusDays(3));
		ticket(2, "T-2", NOW.minusDays(2));
		ticket(3, "T-3", NOW.minusDays(1));
		SalesPushJob job = job();
		server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(SALES)))
				.andRespond(answering(rejecting("T-2", "line 1: itemCode is required")));

		LinkJobRun run = job.run();

		assertEquals(2, logRows.size(), "two batches: [T-1, T-2] and [T-3]");
		LinkExchange first = logRows.get(0);
		assertEquals("SALES_PUSH", first.getJob());
		assertEquals(ExchangeDirection.UP, first.getDirection());
		assertEquals(2, first.getRecordCount());
		assertEquals(LinkJobResult.WARNING, first.getResult());
		assertEquals("T-2: line 1: itemCode is required", first.getError());
		assertEquals(NOW, first.getExchangeDate());
		assertTrue(first.getDurationMs() >= 0);
		assertEquals(LinkJobResult.SUCCESS, logRows.get(1).getResult());
		assertEquals(1, logRows.get(1).getRecordCount());
		assertEquals(LinkJobResult.WARNING, run.getResult());
		assertEquals("2 sent, 1 rejected, 0 not built, 0 unchanged", run.getMessage());
	}

	@Test
	@DisplayName("Task 2.6: not delivered gives one ERROR row with the state and message; a failed search one ERROR row too")
	void failureRows() {
		ticket(1, "T-1", NOW.minusDays(1));
		SalesPushJob job = job();
		server.expect(requestTo(SALES + "tickets")).andRespond(request -> {
			throw new ConnectException("Connection refused");
		});

		LinkJobRun run = job.run();

		assertEquals(1, logRows.size());
		assertEquals(LinkJobResult.ERROR, logRows.get(0).getResult());
		assertEquals(1, logRows.get(0).getRecordCount());
		assertEquals("OFFLINE: head office unreachable (ConnectException: Connection refused)", logRows.get(0).getError());
		assertEquals(LinkJobResult.ERROR, run.getResult());
		assertTrue(run.getMessage().startsWith("not delivered, documents stay pending (OFFLINE"), run.getMessage());

		logRows.clear();
		searchFails = true;
		SalesPushJob searching = job();
		server.expect(requestTo(SALES + "tickets")).andRespond(acceptingAll());
		searching.run();
		LinkExchange search = logRows.get(0);
		assertEquals(LinkJobResult.ERROR, search.getResult());
		assertEquals(0, search.getRecordCount());
		assertTrue(search.getError().startsWith("search failed: TICKET: IllegalStateException: The query has timed out."),
				search.getError());
	}

	@Test
	@DisplayName("Task 2.6: a log that cannot be written does not break the push: documents SENT, job result SUCCESS, no exception")
	void logFailureDoesNotBreakPush() {
		ticket(1, "T-1", NOW.minusDays(2));
		ticket(2, "T-2", NOW.minusDays(1));
		logDown = true;
		SalesPushJob job = job();
		server.expect(requestTo(SALES + "tickets")).andRespond(acceptingAll());

		LinkJobRun run = job.run();

		server.verify();
		assertEquals(SalesCopyStatus.SENT, stored("T-1").getStatus());
		assertEquals(SalesCopyStatus.SENT, stored("T-2").getStatus());
		assertEquals(LinkJobResult.SUCCESS, run.getResult());
		assertTrue(logRows.isEmpty());
	}

	// --- Stubs ---

	private interface Handler {
		Object handle(String method, Object[] args);
	}

	private static final Object UNHANDLED = new Object();

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "Stub";
				default: break;
			}
			Object result = handler.handle(method.getName(), args == null ? new Object[0] : args);
			if (result == UNHANDLED) {
				throw new UnsupportedOperationException("Unexpected call: " + type.getSimpleName() + "." + method.getName());
			}
			return result;
		});
	}
}
