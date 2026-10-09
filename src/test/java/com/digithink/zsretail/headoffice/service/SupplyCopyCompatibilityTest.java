package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryCopyDTO;
import com.digithink.zsretail.headoffice.dto.ErpInvoiceCopyDTO;
import com.digithink.zsretail.headoffice.model.HoDelivery;
import com.digithink.zsretail.headoffice.model.HoDeliveryLine;
import com.digithink.zsretail.headoffice.model.HoErpInvoice;
import com.digithink.zsretail.headoffice.model.HoErpInvoiceLine;
import com.digithink.zsretail.headoffice.enumeration.DeliveryStatus;
import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceLineType;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 2.2.1, decimal quantities, step 4: with whole quantities, what travels on the supply path is what 2.2.0 sent (the ERP
 * invoice and the BL down to the store, the store's confirmation up). The JSON below was written by the 2.2.0 code
 * (captured before the change); the SHA-256 of each is its fingerprint. The copies down carry no content hash in the
 * code: the JSON itself is what must not change.
 */
class SupplyCopyCompatibilityTest {

	/** The copy mappers of the head office (HoErpInvoiceService / HoDeliveryService.COPY_MAPPER), properties sorted. */
	private static final ObjectMapper SORTED = new ObjectMapper()
			.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

	static HoErpInvoice wholeInvoice() {
		HoErpInvoice invoice = new HoErpInvoice();
		invoice.setBcNumber("FVV26000000123");
		invoice.setDocumentDate(LocalDate.of(2026, 10, 9));
		invoice.setPostingDate(LocalDate.of(2026, 10, 9));
		invoice.setCustomerNo("C-HS01");
		invoice.setCustomerName("Happyness Store 1");
		invoice.setSentAt(LocalDateTime.of(2026, 10, 9, 18, 30));
		invoice.setTotalExclVat(130.0);
		invoice.setTotalVat(24.7);
		invoice.setTotalInclVat(154.7);
		invoice.getLines().add(line(invoice, 10000, ErpInvoiceLineType.ITEM, "VH52-1L", 6, 20.0, 0.0, 120.0, 20.0));
		invoice.getLines().add(line(invoice, 20000, ErpInvoiceLineType.ITEM, "TESTER", 1, 0.0, 0.0, 0.0, null));
		invoice.getLines().add(line(invoice, 30000, ErpInvoiceLineType.OTHER, null, 0, 10.0, null, 10.0, null));
		return invoice;
	}

	private static HoErpInvoiceLine line(HoErpInvoice invoice, int lineNo, ErpInvoiceLineType type, String code,
			int quantity, Double unitPrice, Double discount, Double amount, Double unitCost) {
		HoErpInvoiceLine line = new HoErpInvoiceLine();
		line.setInvoice(invoice);
		line.setLineNo(lineNo);
		line.setLineType(type);
		line.setItemCode(code);
		line.setDescription(code == null ? "Transport" : "Item " + code);
		Whole.setQuantity(line, quantity);
		line.setUnitPrice(unitPrice);
		line.setLineDiscountPercent(discount);
		line.setLineAmount(amount);
		line.setUnitCost(unitCost);
		return line;
	}

	static HoDelivery wholeDelivery() {
		HoDelivery delivery = new HoDelivery();
		delivery.setNumber("BL-000001");
		delivery.setDocumentDate(LocalDate.of(2026, 10, 9));
		delivery.setSentAt(LocalDateTime.of(2026, 10, 9, 18, 30));
		delivery.setStatus(DeliveryStatus.SENT);
		delivery.setNote("Weekly");
		for (int i = 1; i <= 2; i++) {
			HoDeliveryLine line = new HoDeliveryLine();
			line.setLineNo(i);
			line.setItemCode("B00" + i);
			line.setItemName("Item B00" + i);
			Whole.setQuantitySent(line, i == 1 ? 50 : 5);
			delivery.getLines().add(line);
		}
		return delivery;
	}

	static DeliveryConfirmationDTO wholeConfirmation() {
		DeliveryConfirmationDTO confirmation = new DeliveryConfirmationDTO();
		confirmation.setNumber("FVV26000000123");
		confirmation.setReceivedAt("2026-10-10T09:00:00");
		confirmation.setReceivedBy("responsible");
		confirmation.setNote("2 missing");
		confirmation.getLines().add(Whole.confirmationLine(10000, "VH52-1L", 4));
		confirmation.getLines().add(Whole.confirmationLine(20000, "TESTER", 1));
		return confirmation;
	}

	private static String sha256(String json) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
		return String.format("%064x", new BigInteger(1, digest));
	}

	static final String ERP_INVOICE_JSON_220 = "{\"customerName\":\"Happyness Store 1\",\"customerNo\":\"C-HS01\",\"documentDate\":\"2026-10-09\",\"invoiceNumber\":\"FVV26000000123\",\"kind\":\"ERP_INVOICE\",\"lines\":[{\"description\":\"Item VH52-1L\",\"itemCode\":\"VH52-1L\",\"lineAmount\":120.0,\"lineDiscountPercent\":0.0,\"lineNo\":10000,\"lineType\":\"ITEM\",\"quantity\":6,\"unitCost\":20.0,\"unitPrice\":20.0},{\"description\":\"Item TESTER\",\"itemCode\":\"TESTER\",\"lineAmount\":0.0,\"lineDiscountPercent\":0.0,\"lineNo\":20000,\"lineType\":\"ITEM\",\"quantity\":1,\"unitCost\":null,\"unitPrice\":0.0},{\"description\":\"Transport\",\"itemCode\":null,\"lineAmount\":10.0,\"lineDiscountPercent\":null,\"lineNo\":30000,\"lineType\":\"OTHER\",\"quantity\":0,\"unitCost\":null,\"unitPrice\":10.0}],\"postingDate\":\"2026-10-09\",\"sellerName\":\"Head office\",\"sentAt\":\"2026-10-09T18:30:00\",\"totalExclVat\":130.0,\"totalInclVat\":154.7,\"totalVat\":24.7}";
	static final String ERP_INVOICE_SHA_220 = "3682c34a005a6ed0203d272c573bdc25ee883c1014959858fca19d416b1dca36";
	static final String DELIVERY_JSON_220 = "{\"documentDate\":\"2026-10-09\",\"kind\":\"BL\",\"lines\":[{\"itemCode\":\"B001\",\"itemName\":\"Item B001\",\"lineNo\":1,\"quantitySent\":50},{\"itemCode\":\"B002\",\"itemName\":\"Item B002\",\"lineNo\":2,\"quantitySent\":5}],\"note\":\"Weekly\",\"number\":\"BL-000001\",\"sentAt\":\"2026-10-09T18:30:00\",\"status\":\"SENT\"}";
	static final String DELIVERY_SHA_220 = "38a587c8743f72da8d634bdc7e7fc87aaa124920abd5b5fafcbd83dccef2fdc1";
	static final String CONFIRMATION_JSON_220 = "{\"lines\":[{\"itemCode\":\"VH52-1L\",\"lineNo\":10000,\"quantityReceived\":4},{\"itemCode\":\"TESTER\",\"lineNo\":20000,\"quantityReceived\":1}],\"note\":\"2 missing\",\"number\":\"FVV26000000123\",\"receivedAt\":\"2026-10-10T09:00:00\",\"receivedBy\":\"responsible\"}";
	static final String CONFIRMATION_SHA_220 = "b3575e6d62596322adbc53a40628e6ac35b3428acffed9ead60dd36a93eeb0be";

	@Test
	@DisplayName("2.2.1: with whole quantities, the ERP invoice copy, the BL copy and the confirmation are the JSON of 2.2.0")
	void wholeQuantitiesAsIn220() throws Exception {
		String invoice = SORTED.writeValueAsString(ErpInvoiceCopyDTO.of(wholeInvoice(), "Head office"));
		String delivery = SORTED.writeValueAsString(DeliveryCopyDTO.of(wholeDelivery()));
		String confirmation = SORTED.writeValueAsString(wholeConfirmation());
		assertEquals(ERP_INVOICE_JSON_220, invoice);
		assertEquals(DELIVERY_JSON_220, delivery);
		assertEquals(CONFIRMATION_JSON_220, confirmation);
		assertEquals(ERP_INVOICE_SHA_220, sha256(invoice));
		assertEquals(DELIVERY_SHA_220, sha256(delivery));
		assertEquals(CONFIRMATION_SHA_220, sha256(confirmation));
	}

	/** The whole quantities set through the types of the build that runs (Integer in 2.2.0, BigDecimal in 2.2.1). */
	static final class Whole {
		static void setQuantity(HoErpInvoiceLine line, int quantity) {
			line.setQuantity(java.math.BigDecimal.valueOf(quantity));
		}

		static void setQuantitySent(HoDeliveryLine line, int quantity) {
			line.setQuantitySent(java.math.BigDecimal.valueOf(quantity));
		}

		static DeliveryConfirmationDTO.Line confirmationLine(int lineNo, String itemCode, int quantity) {
			return new DeliveryConfirmationDTO.Line(lineNo, itemCode, java.math.BigDecimal.valueOf(quantity));
		}
	}
}
