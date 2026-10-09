package com.digithink.zsretail.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.headoffice.dto.PaymentCopyDTO;
import com.digithink.zsretail.headoffice.dto.ReturnCopyDTO;
import com.digithink.zsretail.headoffice.dto.SessionCopyDTO;
import com.digithink.zsretail.headoffice.dto.SessionCountCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketCopyDTO;
import com.digithink.zsretail.headoffice.dto.TicketLineCopyDTO;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.Payment;
import com.digithink.zsretail.model.PaymentMethod;
import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.model.ReturnHeader;
import com.digithink.zsretail.model.ReturnLine;
import com.digithink.zsretail.model.ReturnVoucher;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.SessionCashCount;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.CounterType;
import com.digithink.zsretail.model.enumeration.ReturnType;
import com.digithink.zsretail.model.enumeration.SessionStatus;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Head office plan, task 2.2: the copies of a ticket, a return and a session closing. Every reference travels as a
 * business code plus a readable name, never a database id; lines keep the store's order. Plain JUnit on entities built
 * in memory.
 */
class SalesCopyMapperTest {

	/** Database ids used in the entities: none of them may appear in a copy. */
	private static final long[] IDS = { 987001, 987002, 987003, 987004, 987005, 987006, 987007, 987008, 987009,
			987010, 987011, 987012, 987013, 987014, 987015, 987016, 987017 };

	private static final LocalDateTime SOLD = LocalDateTime.of(2026, 10, 3, 10, 15, 30);

	/** Like the head office's Spring Boot mapper: java.time as ISO strings, unknown fields ignored. */
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	private static UserAccount user(long id, String login, String name) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(login);
		user.setFullName(name);
		return user;
	}

	private static CashierSession session(long id, String number) {
		CashierSession session = new CashierSession();
		session.setId(id);
		session.setSessionNumber(number);
		return session;
	}

	private static Item item(long id, String code, String name) {
		Item item = new Item();
		item.setId(id);
		item.setItemCode(code);
		item.setName(name);
		return item;
	}

	private static PaymentMethod method(long id, String code, String name) {
		PaymentMethod method = new PaymentMethod();
		method.setId(id);
		method.setCode(code);
		method.setName(name);
		return method;
	}

	private static Promotion promotion(long id, String code, String name) {
		Promotion promotion = new Promotion();
		promotion.setId(id);
		promotion.setCode(code);
		promotion.setName(name);
		return promotion;
	}

	/** Every field name in the JSON tree: none may be an id. */
	private static void assertNoDatabaseId(Object copy, ObjectMapper mapper) {
		JsonNode json = mapper.valueToTree(copy);
		List<String> names = new ArrayList<>();
		collectNames(json, names);
		for (String name : names) {
			assertFalse(name.equals("id") || name.endsWith("Id") || name.endsWith("ID"), "field " + name);
		}
		for (long id : IDS) {
			assertFalse(json.toString().contains(Long.toString(id)), "id " + id + " in " + json);
		}
	}

	private static void collectNames(JsonNode node, List<String> names) {
		if (node.isObject()) {
			Iterator<String> fields = node.fieldNames();
			while (fields.hasNext()) {
				String name = fields.next();
				names.add(name);
				collectNames(node.get(name), names);
			}
		} else if (node.isArray()) {
			node.forEach(child -> collectNames(child, names));
		}
	}

	// --- Ticket ---

	private SalesHeader fullTicket() {
		SalesHeader header = new SalesHeader();
		header.setId(IDS[0]);
		header.setSalesNumber("SHOWROOM-S-20261003-0007");
		header.setSalesDate(SOLD);
		header.setCompletedDate(SOLD.plusMinutes(2));
		header.setStatus(TransactionStatus.COMPLETED);
		header.setSubtotal(84.03);
		header.setTaxAmount(15.97);
		header.setDiscountAmount(10.0);
		header.setDiscountPercentage(9.09);
		header.setTotalAmount(100.0);
		header.setPaidAmount(120.0);
		header.setChangeAmount(20.0);
		header.setDiscountSource("PROMOTION");
		header.setPromotion(promotion(IDS[1], "PROMO-CART-10", "Cart 10 TND off"));
		Customer customer = new Customer();
		customer.setId(IDS[2]);
		customer.setCustomerCode("C00042");
		customer.setName("Société Ben Ali");
		header.setCustomer(customer);
		header.setCreatedByUser(user(IDS[3], "cashier1", "Amira Cashier"));
		header.setCashierSession(session(IDS[4], "SES-2026-0112"));
		LoyaltyMember member = new LoyaltyMember();
		member.setId(IDS[5]);
		member.setCardNumber("LYL-000017");
		member.setFirstName("Sami");
		member.setLastName("Trabelsi");
		header.setLoyaltyMember(member);
		header.setLoyaltyPointsEarned(10);
		header.setLoyaltyPointsRedeemed(50);
		header.setLoyaltyDeductionAmount(5.0);
		header.setInvoiced(true);
		header.setInvoiceNumber("FAC-0003");
		header.setTableNumber(4);
		header.setNotes("Split from T-1");
		header.setSynchronizationStatus(com.digithink.zsretail.model.enumeration.SynchronizationStatus.TOTALLY_SYNCHED);
		header.setErpNo("NAV-123");
		return header;
	}

	private static SalesLine line(long id, Item item, int quantity, String discountSource, Promotion promotion) {
		SalesLine line = new SalesLine();
		line.setId(id);
		line.setItem(item);
		line.setQuantity(BigDecimal.valueOf(quantity));
		line.setUnitPrice(16.81);
		line.setUnitPriceIncludingVat(20.0);
		line.setVatPercent(19);
		line.setVatAmount(3.19 * quantity);
		line.setDiscountPercentage(promotion == null ? null : 10.0);
		line.setDiscountAmount(promotion == null ? null : 2.0);
		line.setDiscountSource(discountSource);
		line.setPromotion(promotion);
		line.setLineTotal(16.81 * quantity);
		line.setLineTotalIncludingVat(20.0 * quantity);
		line.setSynched(true);
		return line;
	}

	@Test
	@DisplayName("Ticket: header, loyalty, customer, cashier and session by code and name; lines in store order; payments")
	void ticket() {
		SalesLine second = line(IDS[7], item(IDS[8], "ITM-200", "Shampoo 250 ml"), 1, null, null);
		SalesLine first = line(IDS[6], item(IDS[9], "ITM-100", "Soap"), 2, "PROMOTION",
				promotion(IDS[10], "PROMO-SOAP", "Soap -10%"));
		Payment cash = new Payment();
		cash.setId(IDS[11]);
		cash.setPaymentMethod(method(IDS[12], "ESP", "Espèces"));
		cash.setTotalAmount(70.0);
		cash.setPaymentDate(SOLD.plusMinutes(2));
		Payment cheque = new Payment();
		cheque.setId(IDS[13]);
		cheque.setPaymentMethod(method(IDS[14], "CHQ", "Chèque"));
		cheque.setTotalAmount(50.0);
		cheque.setPaymentDate(SOLD.plusMinutes(2));
		cheque.setTitleNumber("CH-778899");
		cheque.setDueDate(LocalDate.of(2026, 11, 3));

		TicketCopyDTO copy = SalesCopyMapper.ticket(fullTicket(), Arrays.asList(second, first),
				Arrays.asList(cheque, cash));

		assertEquals("SHOWROOM-S-20261003-0007", copy.getSalesNumber());
		assertEquals(SOLD, copy.getSalesDate());
		assertEquals(SOLD.plusMinutes(2), copy.getCompletedDate());
		assertEquals("COMPLETED", copy.getStatus());
		assertEquals(84.03, copy.getSubtotal());
		assertEquals(15.97, copy.getTaxAmount());
		assertEquals(10.0, copy.getDiscountAmount());
		assertEquals(9.09, copy.getDiscountPercentage());
		assertEquals(100.0, copy.getTotalAmount());
		assertEquals(120.0, copy.getPaidAmount());
		assertEquals(20.0, copy.getChangeAmount());
		assertEquals("PROMOTION", copy.getDiscountSource());
		assertEquals("PROMO-CART-10", copy.getPromotionCode());
		assertEquals("Cart 10 TND off", copy.getPromotionName());
		assertEquals("C00042", copy.getCustomerCode());
		assertEquals("Société Ben Ali", copy.getCustomerName());
		assertEquals("cashier1", copy.getCashierLogin());
		assertEquals("Amira Cashier", copy.getCashierName());
		assertEquals("SES-2026-0112", copy.getSessionNumber());
		assertEquals("LYL-000017", copy.getLoyaltyCardNumber());
		assertEquals("Sami Trabelsi", copy.getLoyaltyMemberName());
		assertEquals(10, copy.getLoyaltyPointsEarned());
		assertEquals(50, copy.getLoyaltyPointsRedeemed());
		assertEquals(5.0, copy.getLoyaltyDeductionAmount());
		assertEquals(true, copy.getInvoiced());
		assertEquals("FAC-0003", copy.getInvoiceNumber());
		assertEquals(4, copy.getTableNumber());
		assertEquals("Split from T-1", copy.getNotes());

		assertEquals(2, copy.getLines().size());
		TicketLineCopyDTO line1 = copy.getLines().get(0);
		assertEquals(1, line1.getLineNo());
		assertEquals("ITM-100", line1.getItemCode(), "store order: lowest id first");
		assertEquals("Soap", line1.getItemName());
		assertEquals(BigDecimal.valueOf(2), line1.getQuantity());
		assertEquals(16.81, line1.getUnitPrice());
		assertEquals(20.0, line1.getUnitPriceIncludingVat());
		assertEquals(19, line1.getVatPercent());
		assertEquals(10.0, line1.getDiscountPercentage());
		assertEquals(2.0, line1.getDiscountAmount());
		assertEquals("PROMOTION", line1.getDiscountSource());
		assertEquals("PROMO-SOAP", line1.getPromotionCode());
		assertEquals(16.81 * 2, line1.getLineTotal());
		assertEquals(40.0, line1.getLineTotalIncludingVat());
		TicketLineCopyDTO line2 = copy.getLines().get(1);
		assertEquals(2, line2.getLineNo());
		assertEquals("ITM-200", line2.getItemCode());
		assertNull(line2.getDiscountSource());
		assertNull(line2.getPromotionCode());

		assertEquals(2, copy.getPayments().size());
		PaymentCopyDTO payment1 = copy.getPayments().get(0);
		assertEquals("ESP", payment1.getPaymentMethodCode());
		assertEquals("Espèces", payment1.getPaymentMethodName());
		assertEquals(70.0, payment1.getAmount());
		PaymentCopyDTO payment2 = copy.getPayments().get(1);
		assertEquals("CHQ", payment2.getPaymentMethodCode());
		assertEquals(50.0, payment2.getAmount());
		assertEquals("CH-778899", payment2.getTitleNumber());
		assertEquals(LocalDate.of(2026, 11, 3), payment2.getDueDate());

		assertNoDatabaseId(copy, mapper);
		String json = mapper.valueToTree(copy).toString();
		assertFalse(json.contains("NAV-123") || json.contains("SYNCHED"), "no ERP field: " + json);
	}

	@Test
	@DisplayName("Walk-in ticket: no customer, member, promotion or cashier gives empty fields, no failure")
	void walkInTicket() {
		SalesHeader header = new SalesHeader();
		header.setId(IDS[0]);
		header.setSalesNumber("T-2");
		header.setSalesDate(SOLD);
		header.setStatus(TransactionStatus.COMPLETED);

		TicketCopyDTO copy = SalesCopyMapper.ticket(header, Collections.emptyList(), null);

		assertEquals("T-2", copy.getSalesNumber());
		assertNull(copy.getCustomerCode());
		assertNull(copy.getCustomerName());
		assertNull(copy.getLoyaltyCardNumber());
		assertNull(copy.getLoyaltyMemberName());
		assertNull(copy.getPromotionCode());
		assertNull(copy.getCashierLogin());
		assertNull(copy.getSessionNumber());
		assertTrue(copy.getLines().isEmpty());
		assertTrue(copy.getPayments().isEmpty());

		LoyaltyMember member = new LoyaltyMember();
		member.setCardNumber("LYL-000018");
		member.setFirstName(" Nour ");
		header.setLoyaltyMember(member);
		assertEquals("Nour", SalesCopyMapper.ticket(header, null, null).getLoyaltyMemberName());
	}

	// --- Return ---

	@Test
	@DisplayName("Return: original sales number, voucher as written at the return, lines in store order")
	void returnCopy() {
		SalesHeader original = new SalesHeader();
		original.setId(IDS[0]);
		original.setSalesNumber("SHOWROOM-S-20261001-0003");
		ReturnHeader header = new ReturnHeader();
		header.setId(IDS[1]);
		header.setReturnNumber("RET-0005");
		header.setReturnDate(SOLD);
		header.setStatus(TransactionStatus.COMPLETED);
		header.setReturnType(ReturnType.RETURN_VOUCHER);
		header.setOriginalSalesHeader(original);
		header.setTotalReturnAmount(40.0);
		header.setDiscountPercentage(5.0);
		header.setCreatedByUser(user(IDS[2], "cashier2", "Karim"));
		header.setCashierSession(session(IDS[3], "SES-2026-0113"));
		header.setNotes("damaged");
		ReturnVoucher voucher = new ReturnVoucher();
		voucher.setId(IDS[4]);
		voucher.setVoucherNumber("BON-0009");
		voucher.setVoucherAmount(40.0);
		voucher.setExpiryDate(LocalDate.of(2027, 1, 1));
		voucher.setUsedAmount(15.0);
		header.setReturnVoucher(voucher);
		ReturnLine late = new ReturnLine();
		late.setId(IDS[6]);
		late.setItem(item(IDS[7], "ITM-200", "Shampoo 250 ml"));
		late.setQuantity(1);
		late.setUnitPrice(16.81);
		late.setUnitPriceIncludingVat(20.0);
		late.setLineTotal(16.81);
		late.setLineTotalIncludingVat(20.0);
		ReturnLine early = new ReturnLine();
		early.setId(IDS[5]);
		early.setItem(item(IDS[8], "ITM-100", "Soap"));
		early.setQuantity(2);
		early.setUnitPrice(8.40);
		early.setUnitPriceIncludingVat(10.0);
		early.setLineTotal(16.80);
		early.setLineTotalIncludingVat(20.0);
		early.setNotes("box open");

		ReturnCopyDTO copy = SalesCopyMapper.returnCopy(header, Arrays.asList(late, early));

		assertEquals("RET-0005", copy.getReturnNumber());
		assertEquals(SOLD, copy.getReturnDate());
		assertEquals("COMPLETED", copy.getStatus());
		assertEquals("RETURN_VOUCHER", copy.getReturnType());
		assertEquals("SHOWROOM-S-20261001-0003", copy.getOriginalSalesNumber());
		assertEquals(40.0, copy.getTotalReturnAmount());
		assertEquals(5.0, copy.getDiscountPercentage());
		assertEquals("cashier2", copy.getCashierLogin());
		assertEquals("Karim", copy.getCashierName());
		assertEquals("SES-2026-0113", copy.getSessionNumber());
		assertEquals("BON-0009", copy.getVoucherNumber());
		assertEquals(40.0, copy.getVoucherAmount());
		assertEquals(LocalDate.of(2027, 1, 1), copy.getVoucherExpiryDate());
		assertEquals("damaged", copy.getNotes());
		assertEquals(2, copy.getLines().size());
		assertEquals(1, copy.getLines().get(0).getLineNo());
		assertEquals("ITM-100", copy.getLines().get(0).getItemCode());
		assertEquals("Soap", copy.getLines().get(0).getItemName());
		assertEquals(2, copy.getLines().get(0).getQuantity());
		assertEquals(8.40, copy.getLines().get(0).getUnitPrice());
		assertEquals(10.0, copy.getLines().get(0).getUnitPriceIncludingVat());
		assertEquals(16.80, copy.getLines().get(0).getLineTotal());
		assertEquals(20.0, copy.getLines().get(0).getLineTotalIncludingVat());
		assertEquals("box open", copy.getLines().get(0).getNotes());
		assertEquals("ITM-200", copy.getLines().get(1).getItemCode());
		assertNoDatabaseId(copy, mapper);
		assertFalse(mapper.valueToTree(copy).toString().contains("15.0"), "voucher use is not in the copy");

		header.setReturnVoucher(null);
		header.setReturnType(ReturnType.SIMPLE_RETURN);
		ReturnCopyDTO simple = SalesCopyMapper.returnCopy(header, null);
		assertEquals("SIMPLE_RETURN", simple.getReturnType());
		assertNull(simple.getVoucherNumber());
		assertTrue(simple.getLines().isEmpty());
	}

	// --- Session ---

	@Test
	@DisplayName("Session closing: cashier and verifier by login and name, amounts, cash count lines with payment method code")
	void session() {
		CashierSession session = session(IDS[0], "SES-2026-0112");
		session.setStatus(SessionStatus.TERMINATED);
		session.setCashier(user(IDS[1], "cashier1", "Amira Cashier"));
		session.setOpenedAt(SOLD.minusHours(8));
		session.setClosedAt(SOLD);
		session.setOpeningCash(100.0);
		session.setRealCash(870.5);
		session.setPosUserClosureCash(870.0);
		session.setResponsibleClosureCash(870.5);
		session.setVerifiedBy(user(IDS[2], "manager", "Leila Manager"));
		session.setVerifiedAt(SOLD.plusMinutes(30));
		session.setVerificationNotes("0.5 found");
		SessionCashCount cheque = new SessionCashCount();
		cheque.setId(IDS[4]);
		cheque.setCounterType(CounterType.RESPONSIBLE);
		cheque.setPaymentMethod(method(IDS[5], "CHQ", "Chèque"));
		cheque.setDenominationValue(50.0);
		cheque.setQuantity(1);
		cheque.setLineTotal(50.0);
		cheque.setReferenceNumber("CH-778899");
		SessionCashCount cash = new SessionCashCount();
		cash.setId(IDS[3]);
		cash.setCounterType(CounterType.POS_USER);
		cash.setDenominationValue(20.0);
		cash.setQuantity(41);
		cash.setLineTotal(820.0);

		SessionCopyDTO copy = SalesCopyMapper.session(session, Arrays.asList(cheque, cash));

		assertEquals("SES-2026-0112", copy.getSessionNumber());
		assertEquals("TERMINATED", copy.getStatus());
		assertEquals("cashier1", copy.getCashierLogin());
		assertEquals("Amira Cashier", copy.getCashierName());
		assertEquals(SOLD.minusHours(8), copy.getOpenedAt());
		assertEquals(SOLD, copy.getClosedAt());
		assertEquals(100.0, copy.getOpeningCash());
		assertEquals(870.5, copy.getRealCash());
		assertEquals(870.0, copy.getPosUserClosureCash());
		assertEquals(870.5, copy.getResponsibleClosureCash());
		assertEquals("manager", copy.getVerifiedByLogin());
		assertEquals("Leila Manager", copy.getVerifiedByName());
		assertEquals(SOLD.plusMinutes(30), copy.getVerifiedAt());
		assertEquals("0.5 found", copy.getVerificationNotes());
		assertEquals(2, copy.getCounts().size());
		SessionCountCopyDTO count1 = copy.getCounts().get(0);
		assertEquals(1, count1.getLineNo());
		assertEquals("POS_USER", count1.getCounterType());
		assertNull(count1.getPaymentMethodCode(), "cash has no payment method");
		assertEquals(20.0, count1.getDenominationValue());
		assertEquals(41, count1.getQuantity());
		assertEquals(820.0, count1.getLineTotal());
		SessionCountCopyDTO count2 = copy.getCounts().get(1);
		assertEquals("RESPONSIBLE", count2.getCounterType());
		assertEquals("CHQ", count2.getPaymentMethodCode());
		assertEquals("Chèque", count2.getPaymentMethodName());
		assertEquals("CH-778899", count2.getReferenceNumber());
		assertNoDatabaseId(copy, mapper);

		CashierSession closed = session(IDS[6], "SES-2026-0114");
		closed.setStatus(SessionStatus.CLOSED);
		SessionCopyDTO closedCopy = SalesCopyMapper.session(closed, null);
		assertEquals("CLOSED", closedCopy.getStatus());
		assertNull(closedCopy.getVerifiedByLogin());
		assertTrue(closedCopy.getCounts().isEmpty());
	}

	// --- JSON ---

	@Test
	@DisplayName("JSON: dates as ISO strings, read back equal; a store code or unknown field in the body is ignored")
	void json() throws Exception {
		TicketCopyDTO copy = SalesCopyMapper.ticket(fullTicket(),
				Arrays.asList(line(IDS[6], item(IDS[9], "ITM-100", "Soap"), 2, null, null)), null);
		String json = mapper.writeValueAsString(copy);
		assertTrue(json.contains("\"salesDate\":\"2026-10-03T10:15:30\""), json);

		assertEquals(copy, mapper.readValue(json, TicketCopyDTO.class));
		String withStore = json.replaceFirst("\\{", "{\"storeCode\":\"OTHER\",\"id\":5,");
		assertEquals(copy, new ObjectMapper().findAndRegisterModules().readValue(withStore, TicketCopyDTO.class),
				"ignored even by a strict mapper");
	}

	// --- 2.2.1: decimal quantities ---

	/** The full ticket of {@link #ticket()} with whole quantities, read from DECIMAL(18,3) columns (2.000, 1.000). */
	private TicketCopyDTO wholeTicketFromDecimalColumns() {
		SalesLine second = line(IDS[7], item(IDS[8], "ITM-200", "Shampoo 250 ml"), 1, null, null);
		SalesLine first = line(IDS[6], item(IDS[9], "ITM-100", "Soap"), 2, "PROMOTION",
				promotion(IDS[10], "PROMO-SOAP", "Soap -10%"));
		second.setQuantity(new BigDecimal("1.000"));
		first.setQuantity(new BigDecimal("2.000"));
		Payment cash = new Payment();
		cash.setId(IDS[11]);
		cash.setPaymentMethod(method(IDS[12], "ESP", "Espèces"));
		cash.setTotalAmount(120.0);
		cash.setPaymentDate(SOLD.plusMinutes(2));
		return SalesCopyMapper.ticket(fullTicket(), Arrays.asList(second, first), Arrays.asList(cash));
	}

	/** JSON of the whole-quantity ticket as 2.2.0 sends it (sorted properties, as the copy hash reads it). */
	private static final String WHOLE_TICKET_JSON_220 = "{\"cashierLogin\":\"cashier1\",\"cashierName\":\"Amira Cashier\",\"changeAmount\":20.0,\"completedDate\":\"2026-10-03T10:17:30\",\"customerCode\":\"C00042\",\"customerName\":\"Société Ben Ali\",\"discountAmount\":10.0,\"discountPercentage\":9.09,\"discountSource\":\"PROMOTION\",\"invoiceNumber\":\"FAC-0003\",\"invoiced\":true,\"lines\":[{\"discountAmount\":2.0,\"discountPercentage\":10.0,\"discountSource\":\"PROMOTION\",\"itemCode\":\"ITM-100\",\"itemName\":\"Soap\",\"lineNo\":1,\"lineTotal\":33.62,\"lineTotalIncludingVat\":40.0,\"promotionCode\":\"PROMO-SOAP\",\"quantity\":2,\"unitPrice\":16.81,\"unitPriceIncludingVat\":20.0,\"vatAmount\":6.38,\"vatPercent\":19},{\"discountAmount\":null,\"discountPercentage\":null,\"discountSource\":null,\"itemCode\":\"ITM-200\",\"itemName\":\"Shampoo 250 ml\",\"lineNo\":2,\"lineTotal\":16.81,\"lineTotalIncludingVat\":20.0,\"promotionCode\":null,\"quantity\":1,\"unitPrice\":16.81,\"unitPriceIncludingVat\":20.0,\"vatAmount\":3.19,\"vatPercent\":19}],\"loyaltyCardNumber\":\"LYL-000017\",\"loyaltyDeductionAmount\":5.0,\"loyaltyMemberName\":\"Sami Trabelsi\",\"loyaltyPointsEarned\":10,\"loyaltyPointsRedeemed\":50,\"notes\":\"Split from T-1\",\"paidAmount\":120.0,\"payments\":[{\"amount\":120.0,\"dueDate\":null,\"paymentDate\":\"2026-10-03T10:17:30\",\"paymentMethodCode\":\"ESP\",\"paymentMethodName\":\"Espèces\",\"titleNumber\":null}],\"promotionCode\":\"PROMO-CART-10\",\"promotionName\":\"Cart 10 TND off\",\"salesDate\":\"2026-10-03T10:15:30\",\"salesNumber\":\"SHOWROOM-S-20261003-0007\",\"sessionNumber\":\"SES-2026-0112\",\"status\":\"COMPLETED\",\"subtotal\":84.03,\"tableNumber\":4,\"taxAmount\":15.97,\"totalAmount\":100.0}";

	/** The copy hash of that ticket in 2.2.0 (SalesPushService.hash). */
	private static final String WHOLE_TICKET_HASH_220 = "de22aa270c20adfdb8ad74ffd992589da4f1ecd997a0dcee1452ffbc104aaa93";

	@Test
	@DisplayName("2.2.1: a ticket with whole quantities only gives the JSON and the copy hash of 2.2.0 (2, never 2.000)")
	void wholeQuantitiesAsIn220() throws Exception {
		TicketCopyDTO copy = wholeTicketFromDecimalColumns();
		String sorted = mapper.copy().configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
				.writeValueAsString(copy);
		assertEquals(WHOLE_TICKET_JSON_220, sorted);
		assertEquals(WHOLE_TICKET_HASH_220, SalesPushService.hash(copy));
	}
}
