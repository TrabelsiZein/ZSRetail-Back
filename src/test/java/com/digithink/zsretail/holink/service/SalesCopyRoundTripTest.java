package com.digithink.zsretail.holink.service;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.headoffice.dto.SalesCopyAnswerDTO;
import com.digithink.zsretail.headoffice.dto.TicketCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketLineCopyDTO;
import com.digithink.zsretail.headoffice.model.HoTicket;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoTicketRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.headoffice.service.SalesCopyReceiver;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.SalesDocumentRef;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.holink.model.SalesCopy;
import com.digithink.zsretail.holink.model.SalesCopyCursor;
import com.digithink.zsretail.holink.repository.SalesCopyCursorRepository;
import com.digithink.zsretail.holink.repository.SalesCopyRepository;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.service.GeneralSetupService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Head office plan, step 2, item 1: a document that stops being finished after it was sent. The real store side (search
 * and push) talks through a mock HTTP server to the real head office receiver, both over in-memory tables. A ticket
 * sent and then cancelled is sent again and its head office copy takes the new status (still one row); a ticket
 * cancelled before it was finished is never sent. No Spring context.
 */
class SalesCopyRoundTripTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 12, 0);

	/** Like the head office's Spring Boot mapper. */
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	/** Store: tickets (id -> [status, updatedAt]) and the two hol_ tables. */
	private final Map<Long, Object[]> tickets = new LinkedHashMap<>();
	private final Map<Long, SalesCopy> copyTable = new LinkedHashMap<>();
	private final Map<SalesCopyType, SalesCopyCursor> cursorTable = new EnumMap<>(SalesCopyType.class);

	/** Head office: ho_ticket. */
	private final Map<Long, HoTicket> hoTickets = new LinkedHashMap<>();
	private final List<String> sent = new ArrayList<>();

	private SalesPushService push;
	private LocalDateTime now = NOW;

	@BeforeEach
	void setUp() {
		Store store = new Store();
		store.setId(3L);
		store.setCode("SHOWROOM-S");
		SalesCopyReceiver receiver = new SalesCopyReceiver(headOfficeTickets(), null, null,
				stub(StoreRepository.class, (m, a) -> "getOne".equals(m) ? store : UNHANDLED),
				TransactionOperations.withoutTransaction());

		RestTemplate restTemplate = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
		server.expect(ExpectedCount.manyTimes(), requestTo(startsWith("http://localhost:888/zsretail/api/ho/sales/tickets")))
				.andRespond(request -> {
					List<TicketCopyDTO> batch = new ArrayList<>();
					for (TicketCopyDTO copy : mapper.readValue(((MockClientHttpRequest) request).getBodyAsString(),
							TicketCopyDTO[].class)) {
						batch.add(copy);
						sent.add(copy.getSalesNumber() + ":" + copy.getStatus());
					}
					String answer = mapper.writeValueAsString(new SalesCopyAnswerDTO(receiver.receiveTickets(store, batch)));
					return withSuccess(answer, MediaType.APPLICATION_JSON).createResponse(request);
				});
		GeneralSetupService generalSetup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "SHOWROOM-S";
			}
		};
		HeadOfficeClient client = new HeadOfficeClient(restTemplate, generalSetup, "http://localhost:888/zsretail/api",
				"key", "1.12.0");
		SalesCopyRepository copies = storeCopies();
		SalesCopyCursorRepository cursors = stub(SalesCopyCursorRepository.class, (method, args) -> {
			if ("findByDocumentType".equals(method)) {
				return Optional.ofNullable(cursorTable.get(args[0]));
			}
			if ("save".equals(method)) {
				SalesCopyCursor cursor = (SalesCopyCursor) args[0];
				cursorTable.put(cursor.getDocumentType(), cursor);
				return cursor;
			}
			return UNHANDLED;
		});
		SalesDocumentSource source = storeTickets();
		SalesPushSettings settings = new SalesPushSettings("", 50, 60);
		SalesCopyFinder finder = new SalesCopyFinder(source, copies, cursors, settings,
				TransactionOperations.withoutTransaction());
		push = new SalesPushService(finder, source, copies, client, settings, TransactionOperations.withoutTransaction(),
				TransactionOperations.withoutTransaction(), () -> now);
	}

	private void ticket(long id, TransactionStatus status, LocalDateTime updatedAt) {
		tickets.put(id, new Object[] { status.name(), updatedAt });
	}

	private SalesPushService.Cycle cycleAt(LocalDateTime at) {
		now = at;
		return push.runCycle();
	}

	private HoTicket hoTicket(String number) {
		return hoTickets.values().stream().filter(t -> t.getSalesNumber().equals(number)).findFirst().orElse(null);
	}

	private SalesCopy tracked(long id) {
		return copyTable.values().stream().filter(c -> c.getLocalId() == id).findFirst().orElse(null);
	}

	@Test
	@DisplayName("A ticket sent then cancelled is sent again and the head office copy becomes CANCELLED, one row; a ticket cancelled before being finished is never sent")
	void cancelledAfterSending() {
		ticket(1, TransactionStatus.COMPLETED, NOW.minusMinutes(5));
		ticket(2, TransactionStatus.PENDING, NOW.minusMinutes(5)); // parked

		cycleAt(NOW);
		assertEquals(List.of("T-1:COMPLETED"), sent);
		assertEquals("COMPLETED", hoTicket("T-1").getStatus());
		assertEquals(1, hoTickets.size());
		assertEquals(SalesCopyStatus.SENT, tracked(1).getStatus());

		ticket(1, TransactionStatus.CANCELLED, NOW.plusMinutes(1)); // e.g. PUT /sales-header/1
		ticket(2, TransactionStatus.CANCELLED, NOW.plusMinutes(1)); // the parked ticket cancelled
		SalesPushService.Cycle cycle = cycleAt(NOW.plusMinutes(2));

		assertEquals(List.of("T-1:COMPLETED", "T-1:CANCELLED"), sent);
		assertEquals(1, cycle.getChanged());
		assertEquals(1, cycle.getSent());
		assertEquals(1, hoTickets.size(), "replaced, not duplicated");
		assertEquals("CANCELLED", hoTicket("T-1").getStatus());
		assertEquals(1, hoTicket("T-1").getLines().size());
		assertEquals(SalesCopyStatus.SENT, tracked(1).getStatus());
		assertEquals(null, tracked(2), "never finished: never tracked");
		assertEquals(null, hoTicket("T-2"), "never finished: never sent");

		assertTrue(cycleAt(NOW.plusMinutes(5)).isIdle());
		assertEquals(2, sent.size(), "nothing more sent");
	}

	// --- Store side ---

	private SalesDocumentSource storeTickets() {
		return new SalesDocumentSource() {
			@Override
			public List<SalesDocumentRef> findChanged(SalesCopyType type, LocalDateTime from,
					LocalDateTime afterChangedAt, long afterId, LocalDateTime until, int limit) {
				if (type != SalesCopyType.TICKET) {
					return new ArrayList<>();
				}
				return tickets.entrySet().stream().map(e -> new SalesDocumentRef(type, e.getKey(), "T-" + e.getKey(),
						NOW.minusDays(1), (String) e.getValue()[0], (LocalDateTime) e.getValue()[1]))
						.filter(r -> !r.getChangedAt().isAfter(until))
						.filter(r -> r.getChangedAt().isAfter(afterChangedAt)
								|| r.getChangedAt().isEqual(afterChangedAt) && r.getLocalId() > afterId)
						.sorted(Comparator.comparing(SalesDocumentRef::getChangedAt)
								.thenComparing(SalesDocumentRef::getLocalId))
						.limit(limit).collect(Collectors.toList());
			}

			@Override
			public Object loadCopy(SalesCopyType type, Long localId) {
				TicketCopyDTO copy = new TicketCopyDTO();
				copy.setSalesNumber("T-" + localId);
				copy.setSalesDate(NOW.minusDays(1));
				copy.setStatus((String) tickets.get(localId)[0]);
				TicketLineCopyDTO line = new TicketLineCopyDTO();
				line.setLineNo(1);
				line.setItemCode("ITM-100");
				line.setQuantity(BigDecimal.ONE);
				copy.getLines().add(line);
				return copy;
			}
		};
	}

	private SalesCopyRepository storeCopies() {
		return stub(SalesCopyRepository.class, (method, args) -> {
			switch (method) {
				case "findByDocumentTypeAndLocalIdIn":
					Collection<?> ids = (Collection<?>) args[1];
					return copyTable.values().stream()
							.filter(c -> c.getDocumentType() == args[0] && ids.contains(c.getLocalId()))
							.collect(Collectors.toList());
				case "findQueue":
					Collection<?> statuses = (Collection<?>) args[1];
					return copyTable.values().stream()
							.filter(c -> c.getDocumentType() == args[0] && statuses.contains(c.getStatus()))
							.sorted(Comparator.comparing(SalesCopy::getAttempts).thenComparing(SalesCopy::getId))
							.limit(((Pageable) args[2]).getPageSize()).collect(Collectors.toList());
				case "saveAll":
					for (Object o : (Iterable<?>) args[0]) {
						SalesCopy copy = (SalesCopy) o;
						if (copy.getId() == null) {
							copy.setId((long) copyTable.size() + 1);
						}
						copyTable.put(copy.getId(), copy);
					}
					return args[0];
				default:
					return UNHANDLED;
			}
		});
	}

	// --- Head office side ---

	private HoTicketRepository headOfficeTickets() {
		return stub(HoTicketRepository.class, (method, args) -> {
			switch (method) {
				case "findByStoreIdAndSalesNumber":
					return hoTickets.values().stream().filter(t -> Objects.equals(t.getStore().getId(), args[0])
							&& t.getSalesNumber().equals(args[1])).findFirst();
				case "save":
					HoTicket row = (HoTicket) args[0];
					if (row.getId() == null) {
						row.setId((long) hoTickets.size() + 1);
					}
					hoTickets.put(row.getId(), row);
					return row;
				default:
					return UNHANDLED;
			}
		});
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
