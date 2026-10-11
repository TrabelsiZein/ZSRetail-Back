package com.digithink.zsretail.store.rfid.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ReturnLine;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.rfid.model.RfidSaleTag;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.rfid.service.RfidPosService;
import com.digithink.zsretail.store.rfid.service.RfidReadService;
import com.digithink.zsretail.store.support.FakeRfidReader;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * RFID POS step: POST /rfid/pos-read (a read of my machine's reader sorted for the cart) and GET /rfid/ticket-tags.
 * Standalone MockMvc over the controller, the in-memory tables, the fake reader.
 */
class RfidPosAPITest {

	static final String DESK = "192.168.100.200:9090";

	final InMemoryRfid rfid = new InMemoryRfid();
	final FakeRfidReader device = new FakeRfidReader();
	final Map<Long, SalesHeader> tickets = new LinkedHashMap<>();
	final SalesHeaderRepository ticketRepository = (SalesHeaderRepository) Proxy.newProxyInstance(
			SalesHeaderRepository.class.getClassLoader(), new Class<?>[] { SalesHeaderRepository.class },
			(self, method, args) -> {
				switch (method.getName()) {
				case "findById":
					return Optional.ofNullable(tickets.get(args[0]));
				case "findBySalesNumber":
					return tickets.values().stream().filter(t -> t.getSalesNumber().equals(args[0])).findFirst();
				default:
					throw new UnsupportedOperationException(method.getName());
				}
			});
	final RfidPosService pos = new RfidPosService(new RfidReadService(rfid.readerService, rfid.machineService, device),
			rfid.machineService, rfid.tagRepository, rfid.saleTagRepository, ticketRepository);
	final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RfidPosAPI(pos)).build();

	final RfidReader desk = rfid.reader("Caisse 1", "192.168.100.200", 9090);
	final Item shirt = rfid.items.item("SHIRT", 20.0, null);
	final Item jeans = rfid.items.item("JEANS", 50.0, null);
	long nextId = 700;

	@Test
	@DisplayName("No machine at my address, or a machine with no reader: 409 \"This POS machine (address) has no reader.\"; the reader is not called")
	void noReader() throws Exception {
		mvc.perform(RfidAPITest.from("::ffff:192.168.1.99", post("/rfid/pos-read"))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("This POS machine (192.168.1.99) has no reader."))
				.andExpect(jsonPath("$.reason").value("NOT_READY"));
		rfid.machine("PC bureau", "192.168.1.20", null);
		mvc.perform(RfidAPITest.from("192.168.1.20", post("/rfid/pos-read"))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("This POS machine (192.168.1.20) has no reader."));
		assertEquals(0, device.calls.size());
	}

	@Test
	@DisplayName("A read sorted for the cart: added per item, already sold, unknown, not read clearly, in the cart, on a pending ticket (not the one open)")
	void readSortedForTheCart() throws Exception {
		rfid.machine("PC caisse 1", "192.168.1.11", desk);
		rfid.tag("A1", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("A2", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("A3", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("A4", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("A5", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("A6", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("D1", jeans, RfidTagStatus.IN_STOCK);
		rfid.tag("C1", shirt, RfidTagStatus.SOLD);
		held("A5", ticket("T-OTHER", TransactionStatus.PENDING));
		SalesHeader open = ticket("T-OPEN", TransactionStatus.PENDING);
		held("A6", open);
		held("D1", ticket("T-GONE", TransactionStatus.CANCELLED));
		device.report(DESK, "A1", -40).report(DESK, "D1", -42).report(DESK, "A2", -45).report(DESK, "C1", -41)
				.report(DESK, "ABCDEF01", -44).report(DESK, "A3", -70).report(DESK, "A4", -43).report(DESK, "A5", -43)
				.report(DESK, "A6", -46);

		mvc.perform(RfidAPITest.from("192.168.1.11", post("/rfid/pos-read").contentType(MediaType.APPLICATION_JSON)
				.content("{\"pendingTicketId\": " + open.getId() + ", \"cartEpcs\": [\"a4\"]}")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.posMachineName").value("PC caisse 1"))
				.andExpect(jsonPath("$.readerName").value("Caisse 1"))
				.andExpect(jsonPath("$.added").value(4))
				.andExpect(jsonPath("$.alreadySold").value(1))
				.andExpect(jsonPath("$.unknown").value(1))
				.andExpect(jsonPath("$.notReadClearly").value(1))
				.andExpect(jsonPath("$.inCart").value(1))
				.andExpect(jsonPath("$.onPendingTicket").value(1))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[?(@.itemCode == 'SHIRT')].epcs[*]").value(
						org.hamcrest.Matchers.containsInAnyOrder("A1", "A2", "A6")))
				.andExpect(jsonPath("$.items[?(@.itemCode == 'SHIRT')].itemId").value(shirt.getId().intValue()))
				.andExpect(jsonPath("$.items[?(@.itemCode == 'JEANS')].epcs[*]").value(
						org.hamcrest.Matchers.contains("D1")));
		assertEquals(1, device.calls.size(), "one read: " + device.calls);
	}

	@Test
	@DisplayName("Without a body (a new ticket, an empty cart): every tag in stock is added, a tag on any pending ticket is not")
	void readWithoutBody() throws Exception {
		rfid.machine("PC caisse 1", "192.168.1.11", desk);
		rfid.tag("A1", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("A2", shirt, RfidTagStatus.IN_STOCK);
		held("A2", ticket("T-OTHER", TransactionStatus.PENDING));
		device.report(DESK, "A1", -40).report(DESK, "A2", -40);
		mvc.perform(RfidAPITest.from("192.168.1.11", post("/rfid/pos-read"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.added").value(1)).andExpect(jsonPath("$.onPendingTicket").value(1));
	}

	@Test
	@DisplayName("GET /rfid/ticket-tags: by id or number, each tag with its item, its paid line and whether returned; 404, 400")
	void ticketTags() throws Exception {
		SalesHeader paid = ticket("LOC001-0001", TransactionStatus.COMPLETED);
		SalesLine line = new SalesLine();
		line.setId(nextId++);
		line.setQuantity(BigDecimal.valueOf(2));
		RfidSaleTag first = held("A1", paid);
		first.setSalesLine(line);
		RfidSaleTag second = held("A2", paid);
		second.setSalesLine(line);
		ReturnLine back = new ReturnLine();
		back.setId(nextId++);
		second.setReturnLine(back);

		mvc.perform(RfidAPITest.from("192.168.1.11", get("/rfid/ticket-tags").param("ticketNumber", "LOC001-0001")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].epc").value("A1"))
				.andExpect(jsonPath("$[0].itemId").value(shirt.getId().intValue()))
				.andExpect(jsonPath("$[0].salesLineId").value(line.getId().intValue()))
				.andExpect(jsonPath("$[0].returned").value(false))
				.andExpect(jsonPath("$[1].returned").value(true));
		mvc.perform(RfidAPITest.from("192.168.1.11", get("/rfid/ticket-tags").param("salesHeaderId",
				String.valueOf(paid.getId())))).andExpect(jsonPath("$.length()").value(2));
		mvc.perform(RfidAPITest.from("192.168.1.11", get("/rfid/ticket-tags").param("ticketNumber", "NONE")))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No ticket NONE."));
		mvc.perform(RfidAPITest.from("192.168.1.11", get("/rfid/ticket-tags"))).andExpect(status().isBadRequest());
	}

	private SalesHeader ticket(String number, TransactionStatus status) {
		SalesHeader ticket = new SalesHeader();
		ticket.setId(nextId++);
		ticket.setSalesNumber(number);
		ticket.setStatus(status);
		tickets.put(ticket.getId(), ticket);
		return ticket;
	}

	/** A row of rfid_sale_tag: this ticket holds the tag of this EPC (its item). */
	private RfidSaleTag held(String epc, SalesHeader ticket) {
		RfidSaleTag row = new RfidSaleTag();
		row.setSalesHeader(ticket);
		row.setEpc(epc);
		row.setItem(rfid.tagRepository.findByEpc(epc).map(t -> t.getItem()).orElse(shirt));
		return rfid.saleTagRepository.save(row);
	}
}
