package com.digithink.zsretail.holink.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryInputDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyAnswerDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.headoffice.dto.StockReportDTO;
import com.digithink.zsretail.headoffice.enumeration.DeliveryStatus;
import com.digithink.zsretail.headoffice.model.HoDelivery;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.CopiesDownFeed;
import com.digithink.zsretail.headoffice.service.HoCatalogueService;
import com.digithink.zsretail.headoffice.service.HoDeliveryService;
import com.digithink.zsretail.headoffice.service.HoNetworkStockService;
import com.digithink.zsretail.headoffice.service.InMemoryDeliveries;
import com.digithink.zsretail.headoffice.service.InMemoryDownTables;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.DownApplyResult;
import com.digithink.zsretail.holink.dto.ReceivedDeliveryDTO;
import com.digithink.zsretail.holink.dto.ReceptionInputDTO;
import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.enumeration.ReceivedDeliveryStatus;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.holink.model.LinkRight;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.holink.repository.LinkRightRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.StockMovement;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.model.enumeration.StockMovementType;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryLoyalty;
import com.digithink.zsretail.support.InMemoryNetworkStock;
import com.digithink.zsretail.support.InMemoryReceivedDeliveries;
import com.digithink.zsretail.support.InMemoryStock;
import com.digithink.zsretail.support.InMemoryStoreLink;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, tasks 7A.3 and 7A.4, end to end: the real head office (HoDeliveryService, HoCatalogueService,
 * CopiesDownFeed) and the real store B (CopiesDownPuller with CatalogueDownHandler and SupplyDownHandler,
 * DeliveryReceptionService, SupplyPushService, the real HeadOfficeClient) talking through MockRestServiceServer, each
 * over its own in-memory tables. A BL reaches its store only; the store confirms with a difference, its stock goes up
 * once, the head office sets Received with the confirmed quantities; offline confirmation; a lost answer; a missing
 * item waits and its stock goes in once; the head office receiver's rules.
 */
class SupplyRoundTripTest {

	private static final String URL = "http://ho.test/zsretail/api";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 10, 0);
	private static final ObjectMapper MAPPER = new ObjectMapper()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	// Head office
	private final InMemoryCatalogue ho = new InMemoryCatalogue(1);
	private final InMemoryStock hoStock = new InMemoryStock(ho);
	private final InMemoryDeliveries hoTables = new InMemoryDeliveries(ho);
	private CopiesDownFeed feed;
	private HoDeliveryService hoDeliveries;
	private final Map<String, Store> stores = new LinkedHashMap<>();
	private final InMemoryNetworkStock network = new InMemoryNetworkStock();
	private HoNetworkStockService hoNetwork;

	// Store B
	private final InMemoryCatalogue db = new InMemoryCatalogue(100_000);
	private final InMemoryStock stock = new InMemoryStock(db);
	private final InMemoryStoreLink link = new InMemoryStoreLink(new InMemoryLoyalty(500_000));
	private final InMemoryReceivedDeliveries received = new InMemoryReceivedDeliveries(900_000);
	private final Map<String, LinkRight> rights = new HashMap<>();
	private CopiesDownPuller puller;
	private SupplyDownHandler supplyHandler;
	private DeliveryReceptionService reception;
	private SupplyPushService push;

	// The line between them
	private boolean headOfficeDown;
	private boolean loseNextAnswer;

	private Store b;
	private Store c;

	@BeforeEach
	void setUp() {
		CopiesDownFeed[] holder = new CopiesDownFeed[1];
		HoCatalogueService catalogue = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(),
				hoStock.itemRepository(), ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(),
				() -> holder[0], TransactionOperations.withoutTransaction());
		hoDeliveries = hoTables.service(hoStock, () -> holder[0], () -> NOW);
		hoNetwork = new HoNetworkStockService(network.storeStockRepository(ho), ho.storeRepository(),
				TransactionOperations.withoutTransaction(), () -> NOW);
		feed = new InMemoryDownTables().feed(Arrays.asList(catalogue, hoDeliveries));
		holder[0] = feed;
		ItemFamily f1 = ho.family("F1");
		ItemSubFamily sf1 = ho.subFamily("SF1", f1);
		ho.item("B001", 10.0, sf1).setStockQuantity(100);
		ho.item("B002", 4.0, sf1).setStockQuantity(10);
		ho.item("B009", 7.0, sf1).setStockQuantity(10);
		b = ho.store("B");
		c = ho.store("C");
		stores.put("B", b);
		stores.put("C", c);
		hoDeliveries.initialise();
		feed.initialise(); // the startup backfill: the catalogue for every store

		RestTemplate rest = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(URL))).andRespond(this::headOffice);
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "b";
			}
		};
		HeadOfficeClient client = new HeadOfficeClient(rest, setup, URL, "key", "2.1.0");
		CatalogueCopyWriter writer = new CatalogueCopyWriter(db.familyRepository(), db.subFamilyRepository(),
				db.itemRepository(), db.barcodeRepository(), db.compositionRepository());
		CatalogueDownHandler catalogueHandler = new CatalogueDownHandler(writer, link.downRecordLog(),
				new CatalogueRights(rightRepository()), link.exchangeLog(), TransactionOperations.withoutTransaction());
		reception = new DeliveryReceptionService(received.repository(), stock.itemRepository(), stock.stockService(),
				stock.stockMovementService(), TransactionOperations.withoutTransaction(), () -> NOW);
		supplyHandler = new SupplyDownHandler(reception, link.downRecordLog(), TransactionOperations.withoutTransaction());
		puller = new CopiesDownPuller(client, Arrays.asList(catalogueHandler, supplyHandler), link.cursorRepository(),
				link.exchangeLog(), TransactionOperations.withoutTransaction());
		push = new SupplyPushService(client, received.repository(), network.copyRepository(db), link.exchangeLog(),
				TransactionOperations.withoutTransaction(), () -> NOW, 60);
		puller.runCycle(); // the catalogue reaches B
	}

	private LinkRightRepository rightRepository() {
		return proxy(LinkRightRepository.class, (method, args) -> {
			switch (method) {
				case "findByCode":
					return Optional.ofNullable(rights.get(args[0]));
				case "save":
					rights.put(((LinkRight) args[0]).getCode(), (LinkRight) args[0]);
					return args[0];
				default:
					return UNHANDLED;
			}
		});
	}

	/** A BL validated at the head office for this store. */
	private DeliveryDTO sendBl(Store store, DeliveryInputDTO.Line... lines) {
		DeliveryInputDTO input = new DeliveryInputDTO();
		input.setStoreId(store.getId());
		input.setNote("Monday delivery");
		input.setLines(Arrays.asList(lines));
		return hoDeliveries.validate(hoDeliveries.create(input).getId(), "admin").get();
	}

	private static DeliveryInputDTO.Line line(String itemCode, int quantity) {
		return new DeliveryInputDTO.Line(itemCode, quantity);
	}

	private static ReceptionInputDTO counted(ReceptionInputDTO.Line... lines) {
		ReceptionInputDTO input = new ReceptionInputDTO();
		input.setNote("2 broken");
		input.setLines(Arrays.asList(lines));
		return input;
	}

	private static ReceptionInputDTO.Line count(int lineNo, Integer quantity) {
		return new ReceptionInputDTO.Line(lineNo, quantity);
	}

	private List<StockMovement> deliveriesIn() {
		return stock.movements(StockMovementType.DELIVERY_IN);
	}

	private List<LinkExchange> supplyRows() {
		return link.exchanges.stream().filter(e -> SupplyPushService.JOB_CODE.equals(e.getJob()))
				.collect(Collectors.toList());
	}

	@Test
	@DisplayName("A BL for B reaches B only: TO_RECEIVE with its lines resolved to B's head office items; C pulls nothing")
	void reachesItsStoreOnly() {
		sendBl(b, line("B001", 50), line("B002", 5));

		puller.runCycle();

		ReceivedDelivery bl = received.byNumber("BL-000001");
		assertEquals(ReceivedDeliveryStatus.TO_RECEIVE, bl.getStatus());
		assertEquals(LocalDateTime.of(2026, 10, 5, 10, 0), bl.getSentAt());
		assertEquals("Monday delivery", bl.getNote());
		assertEquals(2, bl.getLines().size());
		ReceivedDeliveryLine first = bl.getLines().get(0);
		assertEquals("B001", first.getItemCode());
		assertEquals(50, first.getQuantitySent());
		assertEquals(db.itemByCode("B001").get().getId(), first.getItemId());
		assertNull(first.getQuantityReceived());
		DownRecord tracked = link.downRecords.get("BL:BL-000001");
		assertEquals(DownRecordStatus.APPLIED, tracked.getStatus());
		assertNull(tracked.getInfo());
		assertEquals(0, stock.stockOf("B001"), "nothing in the stock before the reception");

		assertTrue(feed.pull(c, "SUPPLY", "", 100).getRecords().isEmpty(), "C never sees B's BL");
		puller.runCycle();
		assertEquals(1, received.deliveries.size(), "pulled again: unchanged");
	}

	@Test
	@DisplayName("B confirms 48 of 50 (and B002 as sent): stock +48 and +5 once, two DELIVERY_IN; sent up: Received at the head office with -2")
	void receivedWithDifference() {
		sendBl(b, line("B001", 50), line("B002", 5));
		puller.runCycle();
		Long id = received.byNumber("BL-000001").getId();

		ReceivedDeliveryDTO answer = reception.receive(id, counted(count(1, 48)), "responsible").get();

		assertEquals("RECEIVED", answer.getStatus());
		assertEquals(53, answer.getQuantityReceived());
		assertTrue(answer.isDifference());
		assertEquals("PENDING", answer.getPushStatus());
		assertEquals(48, stock.stockOf("B001"));
		assertEquals(5, stock.stockOf("B002"));
		assertEquals(2, deliveriesIn().size());
		assertEquals(48, deliveriesIn().get(0).getQuantity());
		assertEquals("BL", deliveriesIn().get(0).getReferenceType());
		assertEquals("BL-000001", deliveriesIn().get(0).getNotes());

		SupplyPushService.Cycle cycle = push.runCycle();
		assertEquals(1, cycle.getConfirmationsSent());
		assertEquals(SalesCopyStatus.SENT, received.byNumber("BL-000001").getPushStatus());
		HoDelivery atHeadOffice = hoTables.byNumber("BL-000001");
		assertEquals(DeliveryStatus.RECEIVED, atHeadOffice.getStatus());
		assertEquals(48, atHeadOffice.getLines().get(0).getQuantityReceived());
		assertEquals(5, atHeadOffice.getLines().get(1).getQuantityReceived());
		assertEquals(NOW, atHeadOffice.getReceivedAt());
		assertEquals("responsible", atHeadOffice.getReceivedBy());
		assertEquals("2 broken", atHeadOffice.getStoreNote());
		assertEquals(NOW, atHeadOffice.getConfirmationReceivedAt());
		DeliveryDTO view = hoDeliveries.get(atHeadOffice.getId()).get();
		assertTrue(view.isDifference());
		assertEquals(-2, view.getLines().get(0).getDifference());
		assertEquals(50, hoStock.stockOf("B001"), "the head office stock moved at the validation only");
		assertEquals(LinkJobResult.SUCCESS, supplyRows().get(0).getResult());
		assertTrue(push.runCycle().isIdle(), "sent once");
	}

	@Test
	@DisplayName("Confirming twice: 409, the stock and the movements unchanged")
	void receivedOnce() {
		sendBl(b, line("B001", 50));
		puller.runCycle();
		Long id = received.byNumber("BL-000001").getId();
		reception.receive(id, null, "responsible");

		IllegalStateException again = assertThrows(IllegalStateException.class,
				() -> reception.receive(id, counted(count(1, 40)), "responsible"));

		assertEquals("This BL has already been received: BL-000001.", again.getMessage());
		assertEquals(50, stock.stockOf("B001"));
		assertEquals(1, deliveriesIn().size());
		assertEquals(50, received.byNumber("BL-000001").getLines().get(0).getQuantityReceived());
	}

	@Test
	@DisplayName("Head office stopped: the stock goes up at once, the confirmation waits (one failure row); back: Received there")
	void offline() {
		sendBl(b, line("B001", 50));
		puller.runCycle();
		headOfficeDown = true;

		reception.receive(received.byNumber("BL-000001").getId(), null, "responsible");
		assertEquals(50, stock.stockOf("B001"), "at once, without the head office");
		assertFalse(push.runCycle().isDelivered());
		assertFalse(push.runCycle().isDelivered());

		ReceivedDelivery row = received.byNumber("BL-000001");
		assertEquals(SalesCopyStatus.PENDING, row.getPushStatus());
		assertEquals(0, row.getAttempts(), "an unreachable head office is not an attempt");
		assertEquals(1, supplyRows().size(), "a failure that repeats is written once");
		assertEquals(LinkJobResult.ERROR, supplyRows().get(0).getResult());
		assertEquals(DeliveryStatus.SENT, hoTables.byNumber("BL-000001").getStatus());

		headOfficeDown = false;
		assertEquals(1, push.runCycle().getConfirmationsSent());
		assertEquals(DeliveryStatus.RECEIVED, hoTables.byNumber("BL-000001").getStatus());
		assertEquals(50, stock.stockOf("B001"), "the stock moved once");
	}

	@Test
	@DisplayName("Answer lost after the head office applied it: sent again, accepted as already received, nothing changes there")
	void lostAnswer() {
		sendBl(b, line("B001", 50));
		puller.runCycle();
		reception.receive(received.byNumber("BL-000001").getId(), counted(count(1, 49)), "responsible");
		loseNextAnswer = true;

		assertFalse(push.runCycle().isDelivered());
		assertEquals(SalesCopyStatus.PENDING, received.byNumber("BL-000001").getPushStatus());
		assertEquals(DeliveryStatus.RECEIVED, hoTables.byNumber("BL-000001").getStatus(), "applied there");

		assertEquals(1, push.runCycle().getConfirmationsSent());
		assertEquals(SalesCopyStatus.SENT, received.byNumber("BL-000001").getPushStatus());
		assertEquals(49, hoTables.byNumber("BL-000001").getLines().get(0).getQuantityReceived());
	}

	@Test
	@DisplayName("An item not in B: its line waits (the other is stocked), the confirmation goes up; once the item is here its stock goes in once")
	void missingItemWaits() {
		Item local = db.itemByCode("B009").get();
		db.items.remove(local.getId()); // e.g. its catalogue record still waiting here
		sendBl(b, line("B001", 10), line("B009", 4));
		puller.runCycle();
		assertEquals("items not in this store yet: B009", link.downRecords.get("BL:BL-000001").getInfo());

		ReceivedDeliveryDTO answer = reception.receive(received.byNumber("BL-000001").getId(), null, "responsible")
				.get();
		assertEquals(1, answer.getStockWaiting());
		assertEquals(10, stock.stockOf("B001"));
		assertEquals(1, deliveriesIn().size());
		assertEquals(1, push.runCycle().getConfirmationsSent(), "the confirmation does not wait for the item");
		assertEquals(4, hoTables.byNumber("BL-000001").getLines().get(1).getQuantityReceived());

		DownApplyResult waiting = supplyHandler.retry();
		assertEquals(1, waiting.getWaiting());
		assertEquals("BL:BL-000001: stock in waits: not in this store: item B009", waiting.getFirstProblem());
		assertEquals(1L, reception.counts().get("stockWaiting"));

		db.items.put(local.getId(), local); // the catalogue brings it
		assertEquals(0, supplyHandler.retry().getWaiting());
		assertEquals(4, stock.stockOf("B009"));
		assertEquals(2, deliveriesIn().size());
		supplyHandler.retry();
		puller.runCycle();
		assertEquals(4, stock.stockOf("B009"), "once");
		assertEquals(2, deliveriesIn().size());
		assertEquals(0L, reception.counts().get("stockWaiting"));
	}

	@Test
	@DisplayName("A store item with the same code but not from the head office is never used: the line waits")
	void localItemNotUsed() {
		Item headOfficeItem = db.itemByCode("B009").get();
		db.items.remove(headOfficeItem.getId());
		Item own = db.item("B009", 3.0, null); // origin null: the store's own
		sendBl(b, line("B009", 4));
		puller.runCycle();

		reception.receive(received.byNumber("BL-000001").getId(), null, "responsible");

		assertNull(received.byNumber("BL-000001").getLines().get(0).getItemId());
		assertEquals(0, own.getStockQuantity() == null ? 0 : own.getStockQuantity());
		assertTrue(deliveriesIn().isEmpty());
		assertEquals(RecordOrigin.HEAD_OFFICE, headOfficeItem.getOrigin());
	}

	@Test
	@DisplayName("More received than sent (52 of 50): accepted, stock +52, the head office shows +2")
	void moreThanSent() {
		sendBl(b, line("B001", 50));
		puller.runCycle();
		reception.receive(received.byNumber("BL-000001").getId(), counted(count(1, 52)), "responsible");
		push.runCycle();

		assertEquals(52, stock.stockOf("B001"));
		assertEquals(2, hoDeliveries.get(hoTables.byNumber("BL-000001").getId()).get().getLines().get(0).getDifference());
	}

	@Test
	@DisplayName("Reception rules: unknown line, a line twice, a quantity below 0, a note too long: 400 and nothing changes; unknown BL: empty")
	void receptionRules() {
		sendBl(b, line("B001", 50));
		puller.runCycle();
		Long id = received.byNumber("BL-000001").getId();

		assertEquals("Unknown line 9 on BL-000001.", assertThrows(IllegalArgumentException.class,
				() -> reception.receive(id, counted(count(9, 1)), "r")).getMessage());
		assertEquals("Line 1 is given twice.", assertThrows(IllegalArgumentException.class,
				() -> reception.receive(id, counted(count(1, 1), count(1, 2)), "r")).getMessage());
		assertEquals("Line 1: the quantity received must be a whole number, 0 or more.", assertThrows(
				IllegalArgumentException.class, () -> reception.receive(id, counted(count(1, -1)), "r")).getMessage());
		ReceptionInputDTO longNote = counted();
		longNote.setNote(String.join("", Collections.nCopies(501, "x")));
		assertThrows(IllegalArgumentException.class, () -> reception.receive(id, longNote, "r"));
		assertFalse(reception.receive(999L, null, "r").isPresent());

		assertEquals(ReceivedDeliveryStatus.TO_RECEIVE, received.byNumber("BL-000001").getStatus());
		assertEquals(0, stock.stockOf("B001"));
		assertTrue(deliveriesIn().isEmpty());
		ReceivedDeliveryDTO zero = reception.receive(id, counted(count(1, 0)), "r").get();
		assertEquals(0, zero.getQuantityReceived());
		assertEquals(0, zero.getStockWaiting(), "nothing to add: applied");
		assertTrue(deliveriesIn().isEmpty());
	}

	@Test
	@DisplayName("Head office receiver: another store's BL, unknown, draft, lines not matching, quantities missing: rejected; same again: accepted")
	void headOfficeReceiverRules() {
		sendBl(b, line("B001", 50), line("B002", 5));
		DeliveryInputDTO draft = new DeliveryInputDTO();
		draft.setStoreId(b.getId());
		draft.setLines(Collections.singletonList(line("B001", 1)));
		hoDeliveries.create(draft);

		assertEquals("unknown BL BL-000001 for this store",
				result(c, confirmation("BL-000001", line(1, "B001", 50), line(2, "B002", 5))).getMessage());
		assertEquals("unknown BL BL-000099 for this store", result(b, confirmation("BL-000099")).getMessage());
		assertEquals("number is required", result(b, confirmation(null)).getMessage());
		assertEquals("line 3 does not match the BL",
				result(b, confirmation("BL-000001", line(3, "B001", 1))).getMessage());
		assertEquals("line 1 does not match the BL",
				result(b, confirmation("BL-000001", line(1, "B002", 1), line(2, "B002", 5))).getMessage());
		assertEquals("line 1: quantityReceived must be 0 or more",
				result(b, confirmation("BL-000001", line(1, "B001", null), line(2, "B002", 5))).getMessage());
		assertEquals("every line of the BL is required",
				result(b, confirmation("BL-000001", line(1, "B001", 50))).getMessage());
		assertEquals(DeliveryStatus.SENT, hoTables.byNumber("BL-000001").getStatus(), "nothing applied");

		assertTrue(result(b, confirmation("BL-000001", line(1, "B001", 48), line(2, "B002", 5))).isAccepted());
		assertTrue(result(b, confirmation("BL-000001", line(1, "B001", 48), line(2, "B002", 5))).isAccepted(),
				"the same again: accepted, nothing changes");
		assertEquals("already received with other quantities",
				result(b, confirmation("BL-000001", line(1, "B001", 50), line(2, "B002", 5))).getMessage());
		assertEquals(48, hoTables.byNumber("BL-000001").getLines().get(0).getQuantityReceived());
	}

	private SalesCopyResultDTO result(Store store, DeliveryConfirmationDTO confirmation) {
		return hoDeliveries.receiveConfirmations(store, Collections.singletonList(confirmation)).get(0);
	}

	private static DeliveryConfirmationDTO confirmation(String number, DeliveryConfirmationDTO.Line... lines) {
		DeliveryConfirmationDTO confirmation = new DeliveryConfirmationDTO();
		confirmation.setNumber(number);
		confirmation.setReceivedAt("2026-10-05T10:00:00");
		confirmation.setLines(Arrays.asList(lines));
		return confirmation;
	}

	private static DeliveryConfirmationDTO.Line line(int lineNo, String itemCode, Integer quantity) {
		return new DeliveryConfirmationDTO.Line(lineNo, itemCode, quantity);
	}

	@Test
	@DisplayName("Link page counts: to receive, received, confirmations by status, lines whose stock waits")
	void counts() {
		sendBl(b, line("B001", 5));
		sendBl(b, line("B002", 1));
		puller.runCycle();
		reception.receive(received.byNumber("BL-000001").getId(), null, "r");

		Map<String, Object> counts = reception.counts();
		assertEquals(1L, counts.get("toReceive"));
		assertEquals(1L, counts.get("received"));
		assertEquals("{PENDING=1, SENT=0, ERROR=0}", counts.get("confirmations").toString());
		assertEquals(0L, counts.get("stockWaiting"));
		assertEquals(Arrays.asList("toReceive", "received", "confirmations", "stockWaiting"),
				Arrays.asList(counts.keySet().toArray()));
		assertEquals(2L, reception.list("all", 0, 20).get("totalElements"));
		assertEquals(1L, reception.list("to_receive", 0, 20).get("totalElements"));
		assertThrows(IllegalArgumentException.class, () -> reception.list("LOST", 0, 20));
	}

	// ─── Stock copied up (task 7A.5) ─────────────────────────────

	@Test
	@DisplayName("Stock up: every item the first time, then only what changed; own items flagged; a deleted item removed there")
	void stockCopiedUp() {
		sendBl(b, line("B001", 50));
		puller.runCycle();
		reception.receive(received.byNumber("BL-000001").getId(), null, "responsible");
		Item own = db.item("OWN1", 3.0, null);
		own.setStockQuantity(3);
		assertEquals(4L, push.stockCounts().get("stockToSend"), "B001, B002, B009 and OWN1");

		SupplyPushService.Cycle first = push.runCycle();

		assertEquals(4, first.getStockSent());
		assertEquals(50, network.stockAt(b.getId(), "B001").getQuantity());
		assertEquals(0, network.stockAt(b.getId(), "B002").getQuantity(), "a null stock is sent as 0");
		assertFalse(network.stockAt(b.getId(), "B001").getOwnItem());
		assertTrue(network.stockAt(b.getId(), "OWN1").getOwnItem());
		assertEquals(NOW, network.stockAt(b.getId(), "B001").getStoreTime());
		assertEquals(0L, push.stockCounts().get("stockToSend"));
		assertEquals("2026-10-05T10:00:00", push.stockCounts().get("stockSentAt"));
		assertTrue(push.runCycle().isIdle(), "nothing changed: nothing sent");

		stock.stockService().decrementForSale(db.itemByCode("B001").get().getId(), 2);
		SupplyPushService.Cycle second = push.runCycle();
		assertEquals(1, second.getStockSent(), "only the item that changed");
		assertEquals(48, network.stockAt(b.getId(), "B001").getQuantity());

		db.items.remove(own.getId());
		SupplyPushService.Cycle third = push.runCycle();
		assertEquals(1, third.getStockRemoved());
		assertNull(network.stockAt(b.getId(), "OWN1"));
		assertTrue(network.copies.values().stream().noneMatch(c -> c.getItemId().equals(own.getId())));
		assertTrue(push.runCycle().isIdle());
	}

	@Test
	@DisplayName("Stock up with the head office stopped: nothing is marked sent; back: sent once")
	void stockOffline() {
		db.itemByCode("B001").get().setStockQuantity(7);
		headOfficeDown = true;
		assertFalse(push.runCycle().isDelivered());
		assertTrue(network.copies.isEmpty());
		assertTrue(network.storeStock.isEmpty());

		headOfficeDown = false;
		assertEquals(3, push.runCycle().getStockSent());
		assertEquals(7, network.stockAt(b.getId(), "B001").getQuantity());
	}

	@Test
	@DisplayName("Head office page: its items with its stock and each store's; one store; search; the stores' own items apart")
	void headOfficePage() {
		sendBl(b, line("B001", 50));
		puller.runCycle();
		reception.receive(received.byNumber("BL-000001").getId(), counted(count(1, 48)), "responsible");
		db.item("OWN1", 3.0, null).setStockQuantity(3);
		ho.item("TAX_STAMP", 1.0, null);
		push.runCycle();

		Map<String, Object> page = hoNetwork.page(null, null, 0, 20, false);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> storesShown = (List<Map<String, Object>>) page.get("stores");
		assertEquals(Arrays.asList("B", "C"), storesShown.stream().map(s -> s.get("code")).collect(Collectors.toList()));
		assertEquals("2026-10-05T10:00", storesShown.get(0).get("lastStockAt"));
		assertNull(storesShown.get(1).get("lastStockAt"), "C never sent its stock");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("content");
		assertEquals(Arrays.asList("B001", "B002", "B009"),
				rows.stream().map(r -> r.get("itemCode")).collect(Collectors.toList()), "head office items, no tax stamp");
		assertEquals(50, rows.get(0).get("headOffice"));
		assertEquals("{" + b.getId() + "=48}", rows.get(0).get("byStore").toString());
		assertEquals(3L, page.get("totalElements"));

		assertEquals(1, ((List<?>) hoNetwork.page(c.getId(), null, 0, 20, false).get("stores")).size());
		assertEquals("{}", ((List<Map<String, Object>>) hoNetwork.page(c.getId(), null, 0, 20, false).get("content")).get(0)
				.get("byStore").toString());
		assertEquals(1L, hoNetwork.page(null, "b009", 0, 20, false).get("totalElements"));
		assertThrows(IllegalArgumentException.class, () -> hoNetwork.page(999L, null, 0, 20, false));

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> own = (List<Map<String, Object>>) hoNetwork.ownItems(null, null, 0, 20, false)
				.get("content");
		assertEquals(1, own.size());
		assertEquals("OWN1", own.get(0).get("itemCode"));
		assertEquals("B", own.get(0).get("storeCode"));
		assertEquals(3, own.get(0).get("quantity"));
	}

	@SuppressWarnings("unchecked")
	private static List<Object> codes(Map<String, Object> page) {
		return ((List<Map<String, Object>>) page.get("content")).stream().map(r -> r.get("itemCode"))
				.collect(Collectors.toList());
	}

	@Test
	@DisplayName("Without stock (headoffice.stock.enabled=false): the head office column is null and belowZero looks at"
			+ " the stores only")
	void withoutHeadOfficeStock() {
		sendBl(b, line("B001", 50));
		puller.runCycle();
		reception.receive(received.byNumber("BL-000001").getId(), counted(count(1, 48)), "responsible");
		db.itemByCode("B001").get().setStockQuantity(-2); // B below zero
		ho.itemByCode("B002").get().setStockQuantity(-5); // a head office value left from before: ignored
		push.runCycle();
		HoNetworkStockService noStock = new HoNetworkStockService(network.storeStockRepository(ho), ho.storeRepository(),
				TransactionOperations.withoutTransaction(), () -> NOW, false);

		Map<String, Object> page = noStock.page(null, null, 0, 20, false);
		assertEquals(Arrays.asList("B001", "B002", "B009"), codes(page));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("content");
		rows.forEach(r -> {
			assertTrue(r.containsKey("headOffice"));
			assertNull(r.get("headOffice"), (String) r.get("itemCode"));
		});
		assertEquals("{" + b.getId() + "=-2}", rows.get(0).get("byStore").toString());
		assertEquals(Collections.singletonList("B001"), codes(noStock.page(null, null, 0, 20, true)));
		assertEquals(1L, noStock.page(null, null, 0, 20, true).get("totalElements"));
		assertEquals(Collections.emptyList(), codes(noStock.page(c.getId(), null, 0, 20, true)),
				"C shown alone: nothing below zero, the head office column is not looked at");
		assertEquals(Arrays.asList("B001", "B002"), codes(hoNetwork.page(null, null, 0, 20, true)),
				"the head office that keeps its stock still looks at its column");
	}

	@Test
	@DisplayName("belowZero: only the items below zero at the head office or in a store shown (active stores by default);"
			+ " own items below zero")
	void belowZero() {
		sendBl(b, line("B001", 50));
		puller.runCycle();
		reception.receive(received.byNumber("BL-000001").getId(), counted(count(1, 48)), "responsible");
		db.itemByCode("B001").get().setStockQuantity(-2); // B sold more than it had
		db.item("OWN1", 3.0, null).setStockQuantity(-1);
		db.item("OWN2", 3.0, null).setStockQuantity(4);
		ho.itemByCode("B002").get().setStockQuantity(-5); // the head office below zero
		push.runCycle();

		assertEquals(Arrays.asList("B001", "B002", "B009"), codes(hoNetwork.page(null, null, 0, 20, false)));
		assertEquals(Arrays.asList("B001", "B002"), codes(hoNetwork.page(null, null, 0, 20, true)));
		assertEquals(2L, hoNetwork.page(null, null, 0, 20, true).get("totalElements"));
		assertEquals(Collections.singletonList("B002"), codes(hoNetwork.page(c.getId(), null, 0, 20, true)),
				"C shown alone: only the head office column is below zero");
		assertEquals(Arrays.asList("B001", "B002"), codes(hoNetwork.page(b.getId(), null, 0, 20, true)));
		b.setActive(false);
		assertEquals(Collections.singletonList("B002"), codes(hoNetwork.page(null, null, 0, 20, true)),
				"an inactive store is not shown by default");
		b.setActive(true);
		assertEquals(Collections.singletonList("OWN1"), codes(hoNetwork.ownItems(null, null, 0, 20, true)));
		assertEquals(Arrays.asList("OWN1", "OWN2"), codes(hoNetwork.ownItems(null, null, 0, 20, false)));
	}

	// ─── The head office as the store sees it ────────────────────

	private ClientHttpResponse headOffice(ClientHttpRequest request) throws IOException {
		if (headOfficeDown) {
			throw new ConnectException("Connection refused");
		}
		MockClientHttpRequest call = (MockClientHttpRequest) request;
		Store store = stores.get(call.getHeaders().getFirst("X-Store-Code").trim().toUpperCase());
		String path = call.getURI().getPath().substring("/zsretail/api".length());
		MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(call.getURI()).build().getQueryParams();
		Object answer;
		if (call.getMethod() == HttpMethod.GET && path.startsWith("/ho/down/")) {
			answer = feed.pull(store, path.substring("/ho/down/".length()), decode(query.getFirst("cursor")),
					Integer.valueOf(query.getFirst("limit")));
		} else if (call.getMethod() == HttpMethod.POST && path.equals("/ho/supply/confirmations")) {
			answer = new SalesCopyAnswerDTO(hoDeliveries.receiveConfirmations(store,
					Arrays.asList(MAPPER.readValue(call.getBodyAsString(), DeliveryConfirmationDTO[].class))));
			if (loseNextAnswer) {
				loseNextAnswer = false;
				throw new SocketTimeoutException("Read timed out"); // applied there, the answer never arrives
			}
		} else if (call.getMethod() == HttpMethod.POST && path.equals("/ho/supply/stock")) {
			answer = hoNetwork.receive(store, MAPPER.readValue(call.getBodyAsString(), StockReportDTO.class));
		} else {
			return withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
					.body("{\"error\":\"no route\"}").createResponse(request);
		}
		return withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(MAPPER.writeValueAsString(answer))
				.createResponse(request);
	}

	private static String decode(String value) {
		return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
	}
}
