package com.digithink.zsretail.store.holink.service;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.digithink.zsretail.core.link.dto.CatalogueBarcodeCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueCategoryCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueFamilyCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueItemCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueSectionCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueSubFamilyCopyDTO;
import com.digithink.zsretail.core.link.dto.DeliveryCopyDTO;
import com.digithink.zsretail.core.link.dto.ErpInvoiceCopyDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyProgramCopyDTO;
import com.digithink.zsretail.core.link.dto.PromotionCopyDTO;
import com.digithink.zsretail.core.link.dto.StoreOrderStatusCopyDTO;
import com.digithink.zsretail.core.link.dto.SupplyInvoiceCopyDTO;
import com.digithink.zsretail.core.support.GoldenJson;
import com.digithink.zsretail.headoffice.service.HoLoyaltyService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Boot 4 migration, step 1.2: a copy as the head office sends it (its golden file, HeadOfficeGoldenJsonTest) read by the
 * mapper of the store's handler that receives it, and nothing lost: written again the head office's way, it is the same
 * text. Here because it needs both applications.
 */
class CopiesDownGoldenJsonTest {

	/** The head office's way of writing a copy (the one of its mappers that is public; they all give the same file). */
	private static final ObjectMapper HEAD_OFFICE = HoLoyaltyService.COPY_MAPPER;

	static Stream<Arguments> copies() {
		return Stream.of(Arguments.of(CatalogueDownHandler.COPY_MAPPER, CatalogueBarcodeCopyDTO.class),
				Arguments.of(CatalogueDownHandler.COPY_MAPPER, CatalogueCategoryCopyDTO.class),
				Arguments.of(CatalogueDownHandler.COPY_MAPPER, CatalogueFamilyCopyDTO.class),
				Arguments.of(CatalogueDownHandler.COPY_MAPPER, CatalogueItemCopyDTO.class),
				Arguments.of(CatalogueDownHandler.COPY_MAPPER, CatalogueSectionCopyDTO.class),
				Arguments.of(CatalogueDownHandler.COPY_MAPPER, CatalogueSubFamilyCopyDTO.class),
				Arguments.of(SupplyDownHandler.COPY_MAPPER, DeliveryCopyDTO.class),
				Arguments.of(SupplyDownHandler.COPY_MAPPER, ErpInvoiceCopyDTO.class),
				Arguments.of(LoyaltyDownHandler.COPY_MAPPER, LoyaltyMemberCopyDTO.class),
				Arguments.of(LoyaltyDownHandler.COPY_MAPPER, LoyaltyProgramCopyDTO.class),
				Arguments.of(PromotionDownHandler.COPY_MAPPER, PromotionCopyDTO.class),
				Arguments.of(SupplyDownHandler.COPY_MAPPER, StoreOrderStatusCopyDTO.class),
				Arguments.of(SupplyDownHandler.COPY_MAPPER, SupplyInvoiceCopyDTO.class));
	}

	@ParameterizedTest
	@MethodSource("copies")
	void readByTheHandlerOfTheStore(ObjectMapper handler, Class<?> copy) throws Exception {
		String file = "golden/copies/" + copy.getSimpleName() + ".json";
		Object read = handler.treeToValue(handler.readTree(GoldenJson.read(file)), copy);
		GoldenJson.assertSameAs(file, HEAD_OFFICE.writeValueAsString(HEAD_OFFICE.valueToTree(read)));
	}
}
