package com.digithink.zsretail.store.rfid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.ReturnLine;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.store.rfid.model.RfidSaleTag;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.service.SaleTagHooks.LineTags;
import com.digithink.zsretail.store.service.SaleTagHooks.ReturnedLine;
import com.digithink.zsretail.store.support.InMemoryRfid;

/** RFID POS step: the tags of a ticket held, paid, cancelled and returned (RfidSaleTags over the in-memory tables). */
class RfidSaleTagsTest {

	private final InMemoryRfid rfid = new InMemoryRfid();
	private final RfidSaleTags hooks = new RfidSaleTags(rfid.tagRepository, rfid.saleTagRepository, () -> rfid.now);
	private final Item shirt = rfid.items.item("SHIRT", 20.0, null);
	private final Item jeans = rfid.items.item("JEANS", 50.0, null);
	private long nextId = 500;

	@Test
	@DisplayName("Held: only the tags in stock of the line's item, once, at most the line's pieces; rfid_tag unchanged")
	void heldKeepsInStockTagsOfTheItem() {
		rfid.tag("A1", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("A2", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("A3", shirt, RfidTagStatus.IN_STOCK);
		sold("C1", shirt, "OTHER-1");
		rfid.tag("D1", jeans, RfidTagStatus.IN_STOCK);
		SalesHeader ticket = ticket(TransactionStatus.PENDING);
		SalesLine line = line(ticket, shirt, "2");

		hooks.ticketHeld(ticket, Arrays.asList(new LineTags(line,
				Arrays.asList(" a1 ", "A1", "C1", "D1", "ABCDEF01", "not-an-epc", "A2", "A3"))));

		assertEquals(Arrays.asList("A1", "A2"), epcsOf(ticket), "A3: more tags than the 2 pieces");
		assertTrue(rfid.saleTags.values().stream().allMatch(r -> r.getSalesLine() == null && r.getItem() == shirt));
		assertTrue(rfid.tags.values().stream().filter(t -> !"C1".equals(t.getEpc()))
				.allMatch(t -> t.getStatus() == RfidTagStatus.IN_STOCK && t.getTicketNumber() == null));
	}

	@Test
	@DisplayName("Held again: the tags of now replace those held before (none left: none held); cancelled: none held")
	void heldAgainAndCancelled() {
		rfid.tag("A1", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("A2", shirt, RfidTagStatus.IN_STOCK);
		SalesHeader ticket = ticket(TransactionStatus.PENDING);
		hooks.ticketHeld(ticket, Arrays.asList(new LineTags(line(ticket, shirt, "2"), Arrays.asList("A1", "A2"))));
		hooks.ticketHeld(ticket, Arrays.asList(new LineTags(line(ticket, shirt, "1"), Arrays.asList("A2"))));
		assertEquals(Arrays.asList("A2"), epcsOf(ticket));
		hooks.ticketHeld(ticket, Collections.emptyList());
		assertEquals(Collections.emptyList(), epcsOf(ticket));

		hooks.ticketHeld(ticket, Arrays.asList(new LineTags(line(ticket, shirt, "1"), Arrays.asList("A1"))));
		ticket.setStatus(TransactionStatus.CANCELLED);
		hooks.ticketCancelled(ticket);
		assertEquals(Collections.emptyList(), epcsOf(ticket));
		assertEquals(RfidTagStatus.IN_STOCK, tag("A1").getStatus());
	}

	@Test
	@DisplayName("Paid: tags in stock become SOLD with the ticket number and the time, kept with the paid line; one sold meanwhile is skipped")
	void completedSellsTheTagsInStock() {
		rfid.tag("A1", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("A2", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("D1", jeans, RfidTagStatus.IN_STOCK);
		SalesHeader ticket = ticket(TransactionStatus.PENDING);
		hooks.ticketHeld(ticket, Arrays.asList(new LineTags(line(ticket, shirt, "2"), Arrays.asList("A1", "A2"))));
		sold("A2", shirt, "OTHER-2"); // sold by another ticket while this one was pending

		ticket.setStatus(TransactionStatus.COMPLETED);
		SalesLine shirts = line(ticket, shirt, "3"); // 2 tags, the third piece scanned by its barcode
		SalesLine trousers = line(ticket, jeans, "1");
		hooks.ticketCompleted(ticket, Arrays.asList(new LineTags(shirts, Arrays.asList("A1", "A2")),
				new LineTags(trousers, Arrays.asList("D1"))));

		assertEquals(Arrays.asList("A1", "D1"), epcsOf(ticket));
		assertSame(shirts, row("A1").getSalesLine());
		assertSame(trousers, row("D1").getSalesLine());
		for (String epc : Arrays.asList("A1", "D1")) {
			assertEquals(RfidTagStatus.SOLD, tag(epc).getStatus());
			assertEquals(ticket.getSalesNumber(), tag(epc).getTicketNumber());
			assertEquals(rfid.now, tag(epc).getSoldAt());
		}
		assertEquals("OTHER-2", tag("A2").getTicketNumber(), "the other ticket's sale is left as it is");
	}

	@Test
	@DisplayName("Return of the whole remaining: every tag of the line back in stock, marked with the return line")
	void returnWholeLine() {
		SalesHeader ticket = paid("A1", "A2", "A3");
		SalesLine line = rfid.saleTags.values().iterator().next().getSalesLine();
		ReturnLine back = returnLine(line, "3");

		hooks.returned(returnHeader(), Arrays.asList(new ReturnedLine(back, true)), new ArrayList<>());

		for (String epc : Arrays.asList("A1", "A2", "A3")) {
			assertEquals(RfidTagStatus.IN_STOCK, tag(epc).getStatus());
			assertNull(tag(epc).getTicketNumber());
			assertNull(tag(epc).getSoldAt());
			assertSame(back, row(epc).getReturnLine());
		}
		assertEquals(3, epcsOf(ticket).size(), "the rows stay, the record of the sale");
	}

	@Test
	@DisplayName("Return in part: the tags read among the line's own, at most the quantity; a tag of another ticket and nothing read change nothing")
	void returnPartOfLine() {
		paid("A1", "A2", "A3");
		SalesLine line = rfid.saleTags.values().iterator().next().getSalesLine();
		sold("B1", shirt, "OTHER-3");

		hooks.returned(returnHeader(), Arrays.asList(new ReturnedLine(returnLine(line, "1"), false)), new ArrayList<>());
		assertTrue(Arrays.asList("A1", "A2", "A3").stream().allMatch(e -> tag(e).getStatus() == RfidTagStatus.SOLD),
				"nothing read: the tags stay sold, the return is not blocked");

		ReturnLine back = returnLine(line, "1");
		hooks.returned(returnHeader(), Arrays.asList(new ReturnedLine(back, false)), Arrays.asList("b1", "a3", "A2"));
		assertEquals(RfidTagStatus.IN_STOCK, tag("A3").getStatus());
		assertSame(back, row("A3").getReturnLine());
		assertEquals(RfidTagStatus.SOLD, tag("A2").getStatus(), "at most the 1 piece returned");
		assertEquals("OTHER-3", tag("B1").getTicketNumber());

		ReturnLine rest = returnLine(line, "2");
		hooks.returned(returnHeader(), Arrays.asList(new ReturnedLine(rest, true)), new ArrayList<>());
		assertSame(rest, row("A1").getReturnLine());
		assertSame(rest, row("A2").getReturnLine());
		assertSame(back, row("A3").getReturnLine(), "returned once");
		assertTrue(Arrays.asList("A1", "A2", "A3").stream().allMatch(e -> tag(e).getStatus() == RfidTagStatus.IN_STOCK));
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	/** A ticket paid with one shirt line of these tags (in stock before). */
	private SalesHeader paid(String... epcs) {
		for (String epc : epcs) {
			rfid.tag(epc, shirt, RfidTagStatus.IN_STOCK);
		}
		SalesHeader ticket = ticket(TransactionStatus.COMPLETED);
		hooks.ticketCompleted(ticket,
				Arrays.asList(new LineTags(line(ticket, shirt, String.valueOf(epcs.length)), Arrays.asList(epcs))));
		return ticket;
	}

	private SalesHeader ticket(TransactionStatus status) {
		SalesHeader ticket = new SalesHeader();
		ticket.setId(nextId++);
		ticket.setSalesNumber("T-" + ticket.getId());
		ticket.setStatus(status);
		return ticket;
	}

	private SalesLine line(SalesHeader ticket, Item item, String quantity) {
		SalesLine line = new SalesLine();
		line.setId(nextId++);
		line.setSalesHeader(ticket);
		line.setItem(item);
		line.setQuantity(new BigDecimal(quantity));
		return line;
	}

	private ReturnLine returnLine(SalesLine sold, String quantity) {
		ReturnLine line = new ReturnLine();
		line.setId(nextId++);
		line.setOriginalSalesLine(sold);
		line.setItem(sold.getItem());
		line.setQuantity(new BigDecimal(quantity));
		return line;
	}

	private ReturnHeader returnHeader() {
		ReturnHeader header = new ReturnHeader();
		header.setId(nextId++);
		header.setReturnNumber("R-" + header.getId());
		return header;
	}

	private void sold(String epc, Item item, String ticketNumber) {
		RfidTag tag = rfid.tagRepository.findByEpc(epc).orElseGet(() -> rfid.tag(epc, item, RfidTagStatus.SOLD));
		tag.setStatus(RfidTagStatus.SOLD);
		tag.setTicketNumber(ticketNumber);
	}

	private RfidTag tag(String epc) {
		return rfid.tagRepository.findByEpc(epc).get();
	}

	private RfidSaleTag row(String epc) {
		return rfid.saleTags.values().stream().filter(r -> r.getEpc().equals(epc)).findFirst().get();
	}

	private List<String> epcsOf(SalesHeader ticket) {
		return rfid.saleTagRepository.findBySalesHeader_Id(ticket.getId()).stream().map(RfidSaleTag::getEpc).sorted()
				.collect(Collectors.toList());
	}
}
