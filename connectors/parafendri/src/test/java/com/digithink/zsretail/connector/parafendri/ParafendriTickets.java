package com.digithink.zsretail.connector.parafendri;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.digithink.zsretail.erp.api.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.api.dto.ErpTicketLineDTO;

/**
 * Step 6, the tests' ticket as TicketExportService hands it: two lines sold (one with a promotion and a line discount),
 * a free line (a FREE_QUANTITY promotion: its price, 100 %, 0 TTC), the tax stamp (its ERP code), and a ticket-wide
 * reduction of 3.000 TTC (discount and loyalty points).
 */
public final class ParafendriTickets {

	public static final String TICKET_NO = "PV-GREMDA251031001";

	private ParafendriTickets() {
	}

	public static ErpTicketDTO ticket() {
		ErpTicketDTO ticket = new ErpTicketDTO();
		ticket.setTicketNumber(TICKET_NO);
		ticket.setSaleDate(LocalDateTime.of(2026, 10, 31, 10, 15, 42));
		ticket.setLocationExternalId("PV-GREMDA");
		ticket.setCashierSessionId("SES-0042");
		ticket.setTotalAmount(new BigDecimal("70.0"));
		ticket.setTotalAmountExcludingVat(new BigDecimal("58.983"));
		ticket.setTicketDiscountAmount(new BigDecimal("3.0"));
		ticket.setDiscountPercentage(3.0 / 73.0 * 100.0);
		ticket.getLines().add(line(1, "COS-COF-PAR-0001", "2", "18.90756", null, "45.0", null));
		ticket.getLines().add(line(2, "COS-PAR-HOM-0007", "1", "25.21008", "10.0", "27.0", "PROMO10"));
		ticket.getLines().add(line(3, "COS-COF-PAR-0001", "1", "18.90756", "100.0", "0.0", "1ACHETE1OFFERT"));
		ticket.getLines().add(line(4, "TIMBRE", "1", "1.0", null, "1.0", null));
		return ticket;
	}

	public static ErpTicketLineDTO line(int number, String item, String quantity, String unitPrice, String discount,
			String ttc, String promotion) {
		ErpTicketLineDTO line = new ErpTicketLineDTO();
		line.setLineNumber(number);
		line.setItemExternalId(item);
		line.setQuantity(new BigDecimal(quantity));
		line.setUnitPrice(new BigDecimal(unitPrice));
		line.setDiscountPercentage(discount == null ? null : new BigDecimal(discount));
		line.setTotalAmountIncludingVat(new BigDecimal(ttc));
		line.setPromotionCode(promotion);
		line.setLocationCode("PV-GREMDA");
		return line;
	}
}
