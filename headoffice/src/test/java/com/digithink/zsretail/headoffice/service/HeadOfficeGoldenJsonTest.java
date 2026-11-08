package com.digithink.zsretail.headoffice.service;

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
import com.digithink.zsretail.headoffice.dto.DeliveryDTO;
import com.digithink.zsretail.headoffice.dto.ErpInvoiceDTO;
import com.digithink.zsretail.headoffice.dto.HoStoreOrderDTO;
import com.digithink.zsretail.headoffice.dto.StockPointDTO;
import com.digithink.zsretail.headoffice.dto.StoreListItemDTO;
import com.digithink.zsretail.headoffice.dto.StoreWithKeyDTO;
import com.digithink.zsretail.headoffice.dto.SupplyInvoiceDTO;
import com.digithink.zsretail.headoffice.model.Store;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Boot 4 migration, step 1.2: the head office's side of the link. A copy for the stores is made into a tree by its
 * service's own mapper, kept in the outbox and sent as it is: golden/copies holds that text, read back by the stores' mappers
 * in network-tests (CopiesDownGoldenJsonTest). The admin pages' objects are as the mapper of Spring MVC writes them.
 */
class HeadOfficeGoldenJsonTest {

	private static final ObjectMapper BOOT = GoldenJson.bootMapper();

	static Stream<Arguments> copies() {
		return Stream.of(Arguments.of(HoCatalogueService.COPY_MAPPER, CatalogueBarcodeCopyDTO.class),
				Arguments.of(HoCatalogueService.COPY_MAPPER, CatalogueCategoryCopyDTO.class),
				Arguments.of(HoCatalogueService.COPY_MAPPER, CatalogueFamilyCopyDTO.class),
				Arguments.of(HoCatalogueService.COPY_MAPPER, CatalogueItemCopyDTO.class),
				Arguments.of(HoCatalogueService.COPY_MAPPER, CatalogueSectionCopyDTO.class),
				Arguments.of(HoCatalogueService.COPY_MAPPER, CatalogueSubFamilyCopyDTO.class),
				Arguments.of(HoDeliveryService.COPY_MAPPER, DeliveryCopyDTO.class),
				Arguments.of(HoErpInvoiceService.COPY_MAPPER, ErpInvoiceCopyDTO.class),
				Arguments.of(HoLoyaltyService.COPY_MAPPER, LoyaltyMemberCopyDTO.class),
				Arguments.of(HoLoyaltyService.COPY_MAPPER, LoyaltyProgramCopyDTO.class),
				Arguments.of(HoPromotionService.COPY_MAPPER, PromotionCopyDTO.class),
				Arguments.of(HoStoreOrderService.COPY_MAPPER, StoreOrderStatusCopyDTO.class),
				Arguments.of(HoSupplyInvoiceService.COPY_MAPPER, SupplyInvoiceCopyDTO.class));
	}

	static Stream<Class<?>> served() {
		return Stream.of(DeliveryDTO.class, ErpInvoiceDTO.class, HoStoreOrderDTO.class, StockPointDTO.class,
				StoreListItemDTO.class, StoreWithKeyDTO.class, SupplyInvoiceDTO.class, Store.class);
	}

	@ParameterizedTest
	@MethodSource("copies")
	void copyAsItLeavesForTheStores(ObjectMapper mapper, Class<?> copy) throws Exception {
		GoldenJson.assertMatches("golden/copies/" + copy.getSimpleName() + ".json",
				mapper.writeValueAsString(mapper.valueToTree(GoldenJson.sample(copy))));
	}

	@ParameterizedTest
	@MethodSource("served")
	void written(Class<?> served) throws Exception {
		GoldenJson.assertMatches("golden/headoffice/" + served.getSimpleName() + ".json",
				BOOT.writeValueAsString(GoldenJson.sample(served)));
	}
}
