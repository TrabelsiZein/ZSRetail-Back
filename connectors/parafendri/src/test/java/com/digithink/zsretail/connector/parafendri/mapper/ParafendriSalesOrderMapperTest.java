package com.digithink.zsretail.connector.parafendri.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;

import com.digithink.zsretail.connector.parafendri.ParafendriTickets;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriSalesOrder;
import com.digithink.zsretail.erp.api.dto.ErpTicketDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ParaFendri, step 6: a ticket to the body of POST salesOrders (the sample salesOrder-request.json): the header, the
 * lines numbered 10000, 20000, ... by their position among the lines sent, the price before VAT as stored, the free line
 * at 100 %, the tax stamp's ERP code (without one: no stamp line, warned once), the ticket-wide reduction as
 * discountPercent (as EMTOP's Discount_Percent), invoiceDiscountAmount / invoiceDiscountPercent 0, fenceNo the session.
 */
class ParafendriSalesOrderMapperTest {

	private final ParafendriMapper mapper = new ParafendriMapper();

	static String sample(String name) throws IOException {
		try (InputStream in = ParafendriSalesOrderMapperTest.class.getResourceAsStream("/parafendri/" + name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	@DisplayName("The body of the POST, header and lines, exactly the sample (no orderStatus, no amountIncludingVAT:"
			+ " Business Central sets them)")
	void body() throws Exception {
		List<String> warnings = new ArrayList<>();
		ParafendriSalesOrder order = mapper.salesOrder(ParafendriTickets.ticket(), " C-GREMDA ", "PV-GREMDA", warnings);
		String body = new ObjectMapper().writeValueAsString(order);
		JSONAssert.assertEquals(sample("salesOrder-request.json"), body, true);
		assertEquals(Collections.emptyList(), warnings);
		assertTrue(!body.contains("amountIncludingVAT"), "read-only in BC (HTTP 400 when sent), header or line");
	}

	@Test
	@DisplayName("The fields one by one: dates, totals, the reduction as discountPercent, the lines' numbers and price")
	void fields() {
		ParafendriSalesOrder order = mapper.salesOrder(ParafendriTickets.ticket(), "C-GREMDA", "PV-GREMDA", null);
		assertEquals("2026-10-31", order.getOrderDate());
		assertEquals("2026-10-31", order.getPostingDate());
		assertEquals(new BigDecimal("58.983"), order.getTicketAmount(), "HT after the ticket-wide reduction");
		assertEquals(new BigDecimal("4.10959"), order.getDiscountPercent(), "3 / 73 of the lines' TTC, 5 decimals");
		assertEquals(0, BigDecimal.ZERO.compareTo(order.getInvoiceDiscountAmount()));
		assertEquals(0, BigDecimal.ZERO.compareTo(order.getInvoiceDiscountPercent()));
		assertEquals("SES-0042", order.getFenceNo());
		assertEquals(4, order.getSalesOrderLines().size());
		ParafendriSalesOrder.Line free = order.getSalesOrderLines().get(2);
		assertEquals(Integer.valueOf(30000), free.getLineNo());
		assertEquals(new BigDecimal("18.90756"), free.getUnitPrice(), "never rounded");
		assertEquals(new BigDecimal("100"), free.getLineDiscountPercent());
		assertEquals("", order.getSalesOrderLines().get(0).getPromotionNo(), "no promotion: empty");
	}

	@Test
	@DisplayName("A ticket without a reduction or a session: discountPercent 0, no fenceNo")
	void defaults() {
		ErpTicketDTO ticket = ParafendriTickets.ticket();
		ticket.setTicketDiscountAmount(null);
		ticket.setDiscountPercentage(null);
		ticket.setCashierSessionId(" ");
		ParafendriSalesOrder order = mapper.salesOrder(ticket, "C-GREMDA", "PV-GREMDA", null);
		assertEquals(0, BigDecimal.ZERO.compareTo(order.getDiscountPercent()));
		assertNull(order.getFenceNo());
	}

	@Test
	@DisplayName("The stamp without TAX_STAMP_ERP_ITEM_CODE (item TAX_STAMP): no line, the others renumbered, warned once")
	void stampWithoutItsErpCode() {
		ErpTicketDTO ticket = ParafendriTickets.ticket();
		// The stamp in the middle, as the local item (the General Setup empty): TicketExportService's fallback code
		ticket.getLines().add(1, ParafendriTickets.line(2, ParafendriMapper.LOCAL_TAX_STAMP, "1", "1.0", null, "1.0",
				null));
		ticket.getLines().remove(ticket.getLines().size() - 1);
		List<String> warnings = new ArrayList<>();
		ParafendriSalesOrder order = mapper.salesOrder(ticket, "C-GREMDA", "PV-GREMDA", warnings);
		assertEquals("10000 COS-COF-PAR-0001, 20000 COS-PAR-HOM-0007, 30000 COS-COF-PAR-0001", order.getSalesOrderLines()
				.stream().map(line -> line.getLineNo() + " " + line.getItemNo()).collect(Collectors.joining(", ")));
		assertEquals(Collections.singletonList(ParafendriMapper.NO_TAX_STAMP_ITEM), warnings);
		assertTrue(ParafendriMapper.NO_TAX_STAMP_ITEM.contains("TAX_STAMP_ERP_ITEM_CODE"));
	}
}
