package com.digithink.zsretail.store.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.digithink.zsretail.core.support.GoldenJson;
import com.digithink.zsretail.core.utils.WholeQuantityDeserializer.NotWholeQuantity;
import com.digithink.zsretail.store.holink.dto.ReceivedDeliveryDTO;
import com.digithink.zsretail.store.model.Warranty;
import com.digithink.zsretail.store.rfid.dto.RfidTagDTO;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Boot 4 migration, step 1.2: what the till sends and reads, as the mapper of Spring MVC writes it and reads it back; the
 * four @JsonFormat patterns in both directions; the null into a primitive and the whole quantity on real request bodies.
 */
class TillGoldenJsonTest {

	private static final ObjectMapper MAPPER = GoldenJson.bootMapper();

	static Stream<Class<?>> requests() {
		return Stream.of(CartCalculateRequestDTO.class, ChangePaymentMethodRequestDTO.class, CloseSessionRequestDTO.class,
				PrepareInvoiceRequestDTO.class, PriceCalculateRequestDTO.class, ProcessReturnRequestDTO.class,
				ProcessSaleRequestDTO.class, SplitBillRequestDTO.class);
	}

	static Stream<Class<?>> answers() {
		return Stream.of(CartCalculateResponseDTO.class, DayJournalDTO.class, DayRowDTO.class, EligibleTicketDTO.class,
				InvoiceListDTO.class, PriceCalculateResponseDTO.class, ReceivedDeliveryDTO.class, RfidTagDTO.class,
				SessionCloseTicketDTO.class, SessionDashboardDTO.class, SessionReportTicketDTO.class,
				StoreReportTicketDTO.class, TicketsExportDTO.class, TillGrantDTO.class, Warranty.class);
	}

	private static void golden(Class<?> type) throws Exception {
		GoldenJson.assertMatches("golden/store/" + type.getSimpleName() + ".json",
				MAPPER.writeValueAsString(GoldenJson.sample(type)));
	}

	@ParameterizedTest
	@MethodSource("requests")
	void requestWrittenAndReadBack(Class<?> request) throws Exception {
		golden(request);
		String json = MAPPER.writeValueAsString(GoldenJson.sample(request));
		assertEquals(json, MAPPER.writeValueAsString(MAPPER.readValue(json, request)));
	}

	@ParameterizedTest
	@MethodSource("answers")
	void answerWritten(Class<?> answer) throws Exception {
		golden(answer);
	}

	@Test
	@DisplayName("yyyy-MM-dd, written and read")
	void dayPattern() throws Exception {
		ProcessSaleRequestDTO.PaymentDTO payment = new ProcessSaleRequestDTO.PaymentDTO();
		payment.setDueDate(LocalDate.of(2026, 3, 4));
		assertTrue(MAPPER.writeValueAsString(payment).contains("\"dueDate\":\"2026-03-04\""));
		assertEquals(LocalDate.of(2026, 3, 4),
				MAPPER.readValue("{\"dueDate\":\"2026-03-04\"}", ProcessSaleRequestDTO.PaymentDTO.class).getDueDate());
	}

	@Test
	@DisplayName("yyyy-MM-dd | HH:mm:ss, written and read (every entity's createdAt and updatedAt)")
	void entityPattern() throws Exception {
		Warranty warranty = new Warranty();
		warranty.setCreatedAt(LocalDateTime.of(2026, 3, 4, 5, 6, 7));
		assertTrue(MAPPER.writeValueAsString(warranty).contains("\"createdAt\":\"2026-03-04 | 05:06:07\""));
		assertEquals(LocalDateTime.of(2026, 3, 4, 5, 6, 7),
				MAPPER.readValue("{\"createdAt\":\"2026-03-04 | 05:06:07\"}", Warranty.class).getCreatedAt());
	}

	@Test
	@DisplayName("yyyy-MM-dd HH:mm:ss, written and read")
	void ticketPattern() throws Exception {
		SessionCloseTicketDTO ticket = new SessionCloseTicketDTO();
		ticket.setOpenedAt(LocalDateTime.of(2026, 3, 4, 5, 6, 7));
		assertTrue(MAPPER.writeValueAsString(ticket).contains("\"openedAt\":\"2026-03-04 05:06:07\""));
		assertEquals(LocalDateTime.of(2026, 3, 4, 5, 6, 7),
				MAPPER.readValue("{\"openedAt\":\"2026-03-04 05:06:07\"}", SessionCloseTicketDTO.class).getOpenedAt());
	}

	@Test
	@DisplayName("yyyy-MM-dd'T'HH:mm:ss, written and read")
	void isoPattern() throws Exception {
		ReceivedDeliveryDTO delivery = GoldenJson.sample(ReceivedDeliveryDTO.class);
		String json = MAPPER.writeValueAsString(delivery);
		assertTrue(json.contains("\"2026-03-04T05:06:07\""), json);
		assertEquals(json, MAPPER.writeValueAsString(MAPPER.readValue(json, ReceivedDeliveryDTO.class)));
	}

	@Test
	@DisplayName("A null into a primitive of a request body reads as false (Jackson 3 refuses it unless told)")
	void nullIntoPrimitive() throws Exception {
		assertFalse(MAPPER.readValue("{\"discrepancyConfirmed\":null}", CloseSessionRequestDTO.class).isDiscrepancyConfirmed());
	}

	@Test
	@DisplayName("The refusal of 1.5 on a whole quantity is read: @JsonDeserialize stays in force (Jackson 3 ignores the old import)")
	void wholeQuantity() throws Exception {
		assertEquals(Integer.valueOf(2), MAPPER.readValue("{\"lines\":[{\"freeQuantity\":2.0}]}", ProcessSaleRequestDTO.class)
				.getLines().get(0).getFreeQuantity());
		NotWholeQuantity refused = assertThrows(NotWholeQuantity.class,
				() -> MAPPER.readValue("{\"lines\":[{\"freeQuantity\":1.5}]}", ProcessSaleRequestDTO.class));
		assertEquals("lines[0].freeQuantity", refused.getField());
		// A sale line's own quantity keeps its decimals, the text of the number included
		assertEquals(new BigDecimal("0.250"), MAPPER.readValue("{\"lines\":[{\"quantity\":0.250}]}", ProcessSaleRequestDTO.class)
				.getLines().get(0).getQuantity());
	}
}
