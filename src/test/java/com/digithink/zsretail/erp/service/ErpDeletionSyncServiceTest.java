package com.digithink.zsretail.erp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.digithink.zsretail.erp.dto.ErpDeletionLogEntryDTO;
import com.digithink.zsretail.erp.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.dynamicsnav.connector.DynamicsNavConnector;
import com.digithink.zsretail.erp.dynamicsnav.mapper.DynamicsNavMapper;
import com.digithink.zsretail.erp.spi.NoOpErpConnector;
import com.digithink.zsretail.model.SalesDiscount;
import com.digithink.zsretail.model.SalesPrice;
import com.digithink.zsretail.repository.SalesDiscountRepository;
import com.digithink.zsretail.repository.SalesPriceRepository;

/**
 * Code cleanup, step 2, part 4: the external ids of the deleted prices and discounts (ERP Log, EMTOP), character for
 * character, through the deletion sync as it runs: what it looks up, what it deletes, what it skips.
 */
class ErpDeletionSyncServiceTest {

	private final ErpSynchronizationManager manager = mock(ErpSynchronizationManager.class);
	private final SalesPriceRepository prices = mock(SalesPriceRepository.class);
	private final SalesDiscountRepository discounts = mock(SalesDiscountRepository.class);

	/** The service as the application builds it on a store with Dynamics NAV (its connector builds the ids). */
	private ErpDeletionSyncService service() {
		return new ErpDeletionSyncService(manager, new DynamicsNavConnector(null, new DynamicsNavMapper(), null), prices,
				discounts);
	}

	private static ErpDeletionLogEntryDTO entry(String sourceTable) {
		ErpDeletionLogEntryDTO entry = new ErpDeletionLogEntryDTO();
		entry.setSourceTable(sourceTable);
		return entry;
	}

	@Test
	@DisplayName("The ids looked up, character for character: every field, and the empty ones")
	void externalIds() {
		ErpDeletionLogEntryDTO price = entry("Sales Price");
		price.setItemNo("ART-001");
		price.setSalesType("Customer Price Group");
		price.setSalesCode("DETAIL");
		price.setResponsibilityCenter("MAG01");
		price.setStartingDate("2026-01-15");
		price.setCurrencyCode("EUR");
		price.setVariantCode("V1");
		price.setUnitOfMeasureCode("PCS");
		price.setMinimumQuantity(2.5);
		ErpDeletionLogEntryDTO emptyPrice = entry("Sales Price");
		emptyPrice.setItemNo("ART-002");
		ErpDeletionLogEntryDTO discount = entry("Sales Discount");
		discount.setType("Item");
		discount.setCode("ART-001");
		discount.setSalesType("All Customers");
		discount.setSalesCode("");
		discount.setResponsibilityCenter("MAG01");
		discount.setStartingDate("2026-02-01");
		discount.setAuxiliaryIndex1("A1");
		discount.setAuxiliaryIndex2("A2");
		discount.setAuxiliaryIndex3("A3");
		discount.setAuxiliaryIndex4(0);
		ErpDeletionLogEntryDTO emptyDiscount = entry("Sales Discount");
		List<ErpDeletionLogEntryDTO> log = Arrays.asList(price, emptyPrice, discount, emptyDiscount, entry("Item"),
				entry(null));
		when(manager.pullDeletionLog(any())).thenReturn(log);

		assertEquals(log, service().applyDeletionsFromLog(new ErpSyncFilter()));

		ArgumentCaptor<String> priceIds = ArgumentCaptor.forClass(String.class);
		verify(prices, times(2)).findByErpExternalId(priceIds.capture());
		assertEquals(Arrays.asList("ART-001|Customer Price Group|DETAIL|MAG01|2026-01-15|EUR|V1|PCS|2.5",
				"ART-002||||||||"), priceIds.getAllValues());
		ArgumentCaptor<String> discountIds = ArgumentCaptor.forClass(String.class);
		verify(discounts, times(2)).findByErpExternalId(discountIds.capture());
		assertEquals(Arrays.asList("Item|ART-001|All Customers||MAG01|2026-02-01|A1|A2|A3|0", "|||||||||"),
				discountIds.getAllValues());
		verify(prices, never()).delete(any());
		verify(discounts, never()).delete(any());
	}

	@Test
	@DisplayName("A row found by its id is deleted; an empty log does nothing")
	void deletes() {
		ErpDeletionLogEntryDTO price = entry("Sales Price");
		price.setItemNo("ART-001");
		price.setMinimumQuantity(1.0);
		ErpDeletionLogEntryDTO discount = entry("Sales Discount");
		discount.setCode("ART-001");
		SalesPrice foundPrice = new SalesPrice();
		SalesDiscount foundDiscount = new SalesDiscount();
		when(prices.findByErpExternalId("ART-001||||||||1.0")).thenReturn(Optional.of(foundPrice));
		when(discounts.findByErpExternalId("|ART-001||||||||")).thenReturn(Optional.of(foundDiscount));
		when(manager.pullDeletionLog(any())).thenReturn(Arrays.asList(price, discount));

		service().applyDeletionsFromLog(new ErpSyncFilter());

		verify(prices).delete(foundPrice);
		verify(discounts).delete(foundDiscount);

		when(manager.pullDeletionLog(any())).thenReturn(Arrays.asList());
		assertEquals(Arrays.asList(), service().applyDeletionsFromLog(new ErpSyncFilter()));
	}

	@Test
	@DisplayName("A connector without a deletion log builds no id: nothing looked up")
	void connectorWithoutDeletionLog() {
		when(manager.pullDeletionLog(any())).thenReturn(Arrays.asList(entry("Sales Price"), entry("Sales Discount")));
		new ErpDeletionSyncService(manager, new NoOpErpConnector(), prices, discounts)
				.applyDeletionsFromLog(new ErpSyncFilter());
		verify(prices, never()).findByErpExternalId(any());
		verify(discounts, never()).findByErpExternalId(any());
	}
}
