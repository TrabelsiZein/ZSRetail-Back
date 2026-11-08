package com.digithink.zsretail.core.link.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.digithink.zsretail.core.support.GoldenJson;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Boot 4 migration, step 1.2: every message between a store and its head office, as the mapper of Spring MVC writes it and
 * reads it back: the body the head office's controllers read and the answers they write. The other mappers of the link
 * compare themselves with these files in the store and head office projects.
 */
class LinkDtoGoldenJsonTest {

	private static final ObjectMapper MAPPER = GoldenJson.bootMapper();

	static Stream<Class<?>> messages() {
		return Stream.of(CatalogueBarcodeCopyDTO.class, CatalogueCategoryCopyDTO.class, CatalogueFamilyCopyDTO.class,
				CatalogueItemCopyDTO.class, CatalogueSectionCopyDTO.class, CatalogueSubFamilyCopyDTO.class,
				CopiesDownAnswerDTO.class, DeliveryConfirmationDTO.class, DeliveryCopyDTO.class, ErpInvoiceCopyDTO.class,
				HeadOfficeHeartbeatAnswerDTO.class, HeadOfficeHeartbeatDTO.class, HeadOfficePingDTO.class,
				LoyaltyMemberAnswerDTO.class, LoyaltyMemberCopyDTO.class, LoyaltyMemberEditDTO.class,
				LoyaltyMemberResultDTO.class, LoyaltyMovementCopyDTO.class, LoyaltyPhoneCheckDTO.class,
				LoyaltyPointsAdjustDTO.class, LoyaltyProgramCopyDTO.class, PaymentCopyDTO.class, PromotionCopyDTO.class,
				ReturnCopyDTO.class, ReturnLineCopyDTO.class, SalesCopyAnswerDTO.class, SalesCopyResultDTO.class,
				SessionCopyDTO.class, SessionCountCopyDTO.class, StockReportDTO.class, StoreOrderDTO.class,
				StoreOrderStatusCopyDTO.class, SupplyInvoiceCopyDTO.class, TicketCopyDTO.class, TicketLineCopyDTO.class,
				VariantStockDTO.class);
	}

	@ParameterizedTest
	@MethodSource("messages")
	void writtenAndReadBack(Class<?> message) throws Exception {
		String json = MAPPER.writeValueAsString(GoldenJson.sample(message));
		GoldenJson.assertMatches("golden/link/" + message.getSimpleName() + ".json", json);
		assertEquals(json, MAPPER.writeValueAsString(MAPPER.readValue(json, message)));
	}
}
