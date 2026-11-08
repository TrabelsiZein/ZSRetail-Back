package com.digithink.zsretail.store.holink.service;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.core.link.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.core.link.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.core.link.dto.HeadOfficeHeartbeatAnswerDTO;
import com.digithink.zsretail.core.link.dto.HeadOfficeHeartbeatDTO;
import com.digithink.zsretail.core.link.dto.HeadOfficePingDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberAnswerDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberEditDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberResultDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMovementCopyDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyPhoneCheckDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyPointsAdjustDTO;
import com.digithink.zsretail.core.link.dto.ReturnCopyDTO;
import com.digithink.zsretail.core.link.dto.SalesCopyAnswerDTO;
import com.digithink.zsretail.core.link.dto.SalesCopyResultDTO;
import com.digithink.zsretail.core.link.dto.SessionCopyDTO;
import com.digithink.zsretail.core.link.dto.StockReportDTO;
import com.digithink.zsretail.core.link.dto.StoreOrderDTO;
import com.digithink.zsretail.core.link.dto.TicketCopyDTO;
import com.digithink.zsretail.core.link.dto.VariantStockDTO;
import com.digithink.zsretail.core.support.GoldenJson;
import com.digithink.zsretail.store.holink.client.HeadOfficeClient;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Boot 4 migration, step 1.2: the store's side of the link, on the mappers that really run. What HeadOfficeClient writes is
 * the same text the head office's controllers read in core's files; what the client's RestTemplate reads gives back the
 * same content; the text SalesPushService hashes stays as it is: a change would send every ticket to the head office again.
 */
class LinkMappersGoldenJsonTest {

	private static final ObjectMapper BOOT = GoldenJson.bootMapper();

	private static final ObjectMapper WIRE = (ObjectMapper) ReflectionTestUtils.getField(HeadOfficeClient.class, "WIRE_MAPPER");

	private static final ObjectMapper HASH = (ObjectMapper) ReflectionTestUtils.getField(SalesPushService.class, "HASH_MAPPER");

	/** The converter of a plain RestTemplate: HeadOfficeClient's four are made with new RestTemplate(factory). */
	private static final ObjectMapper CLIENT_READS = new RestTemplate().getMessageConverters().stream()
			.filter(MappingJackson2HttpMessageConverter.class::isInstance)
			.map(converter -> ((MappingJackson2HttpMessageConverter) converter).getObjectMapper()).findFirst().get();

	static Stream<Class<?>> sentByTheStore() {
		return Stream.of(DeliveryConfirmationDTO.class, HeadOfficeHeartbeatDTO.class, LoyaltyMemberEditDTO.class,
				LoyaltyMovementCopyDTO.class, LoyaltyPhoneCheckDTO.class, LoyaltyPointsAdjustDTO.class, ReturnCopyDTO.class,
				SessionCopyDTO.class, StockReportDTO.class, StoreOrderDTO.class, TicketCopyDTO.class);
	}

	static Stream<Class<?>> readByTheStore() {
		return Stream.of(CopiesDownAnswerDTO.class, HeadOfficeHeartbeatAnswerDTO.class, HeadOfficePingDTO.class,
				LoyaltyMemberAnswerDTO.class, LoyaltyMemberResultDTO.class, SalesCopyAnswerDTO.class, SalesCopyResultDTO.class,
				StoreOrderDTO.class, VariantStockDTO.class);
	}

	static Stream<Class<?>> hashedByTheStore() {
		return Stream.of(TicketCopyDTO.class, SessionCopyDTO.class, ReturnCopyDTO.class);
	}

	private static String file(Class<?> message) {
		return "golden/link/" + message.getSimpleName() + ".json";
	}

	@ParameterizedTest
	@MethodSource("sentByTheStore")
	void writtenAsTheHeadOfficeReadsIt(Class<?> message) throws Exception {
		GoldenJson.assertSameAs(file(message), WIRE.writeValueAsString(GoldenJson.sample(message)));
	}

	@ParameterizedTest
	@MethodSource("readByTheStore")
	void readAsTheHeadOfficeWritesIt(Class<?> message) throws Exception {
		Object read = CLIENT_READS.readValue(GoldenJson.read(file(message)), message);
		GoldenJson.assertSameAs(file(message), BOOT.writeValueAsString(read));
	}

	@ParameterizedTest
	@MethodSource("hashedByTheStore")
	void hashedText(Class<?> message) throws Exception {
		GoldenJson.assertExactly("golden/store/hashed-" + message.getSimpleName() + ".json",
				HASH.writeValueAsString(GoldenJson.sample(message)));
	}
}
