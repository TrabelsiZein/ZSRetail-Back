package com.digithink.zsretail.erp.navpospages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;

import com.digithink.zsretail.erp.dto.ErpOperationResult;
import com.digithink.zsretail.erp.dynamicsnav.client.DynamicsNavRestClient;
import com.digithink.zsretail.erp.dynamicsnav.config.DynamicsNavConfig;
import com.digithink.zsretail.erp.dynamicsnav.config.DynamicsNavProperties;
import com.digithink.zsretail.erp.dynamicsnav.connector.DynamicsNavConnector;
import com.digithink.zsretail.erp.dynamicsnav.mapper.DynamicsNavMapper;
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesRestClient;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesConfig;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesStartupCheck;
import com.digithink.zsretail.erp.navpospages.connector.NavPosPagesConnector;
import com.digithink.zsretail.erp.navpospages.reader.NavPosPagesReader;
import com.digithink.zsretail.erp.service.ErpCommunicationService;
import com.digithink.zsretail.erp.service.ErpSynchronizationManager;
import com.digithink.zsretail.erp.spi.ErpConnector;
import com.digithink.zsretail.erp.spi.NoOpErpConnector;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.LocationService;

/**
 * ERP catalogue, step 5: the connector answers empty lists and read-only failures; which ErpConnector the
 * synchronization manager receives in the three situations (nothing enabled, Dynamics NAV, the POS pages), with the
 * real connector classes in a small context; a startup refusal stops that context.
 */
class NavPosPagesConnectorTest {

	@Configuration
	@EnableConfigurationProperties
	static class Binding {
	}

	/** The connector classes of both ERPs and the manager, as the application scans them. */
	private static ApplicationContextRunner context() {
		return new ApplicationContextRunner()
				.withUserConfiguration(Binding.class, NoOpErpConnector.class, DynamicsNavProperties.class,
						DynamicsNavConfig.class, DynamicsNavMapper.class, DynamicsNavRestClient.class,
						DynamicsNavConnector.class, NavPosPagesStartupCheck.class, NavPosPagesProperties.class,
						NavPosPagesConfig.class, NavPosPagesRestClient.class, NavPosPagesReader.class,
						NavPosPagesConnector.class, ErpSynchronizationManager.class)
				// Plain singletons: never called here, and not autowired
				.withInitializer(ctx -> {
					ctx.getBeanFactory().registerSingleton("generalSetupService", mock(GeneralSetupService.class));
					ctx.getBeanFactory().registerSingleton("locationService", mock(LocationService.class));
					ctx.getBeanFactory().registerSingleton("erpCommunicationService", mock(ErpCommunicationService.class));
				});
	}

	private static String[] dynamicsNavKeys() {
		return new String[] { "erp.dynamicsnav.enabled=true", "erp.dynamicsnav.base-url=http://nav.test:7048/NAV/ODataV4",
				"erp.dynamicsnav.company=TEST", "erp.dynamicsnav.domain=D", "erp.dynamicsnav.username=u",
				"erp.dynamicsnav.password=p" };
	}

	private static String[] navPosPagesKeys() {
		return new String[] { "node.type=HEAD_OFFICE", "ownership.catalogue=ERP", "erp.navpospages.enabled=true",
				"erp.navpospages.base-url=" + NavPosPagesTestSupport.BASE_URL, "erp.navpospages.company=HAPPYNESS",
				"erp.navpospages.username=user", "erp.navpospages.password=secret",
				"erp.navpospages.location-code=FRANCHISE", "erp.navpospages.default-vat=19",
				"erp.navpospages.price-includes-vat=true" };
	}

	@Test
	@DisplayName("Every fetch answers an empty list, every push or update a read-only failure")
	void readOnly() {
		NavPosPagesConnector connector = new NavPosPagesConnector();
		assertTrue(connector.fetchItemFamilies(null).isEmpty());
		assertTrue(connector.fetchItemSubFamilies(null).isEmpty());
		assertTrue(connector.fetchItems(null).isEmpty());
		assertTrue(connector.fetchItemBarcodes(null).isEmpty());
		assertTrue(connector.fetchLocations(null).isEmpty());
		assertTrue(connector.fetchCustomers(null).isEmpty());
		assertTrue(connector.fetchSalesPrices(null).isEmpty());
		assertTrue(connector.fetchSalesDiscounts(null).isEmpty());
		assertTrue(connector.fetchDeletionLog(null).isEmpty());
		List<ErpOperationResult> results = new ArrayList<>();
		results.add(connector.pushCustomer(null));
		results.add(connector.pushTicket(null));
		results.add(connector.pushTicketHeader(null));
		results.add(connector.pushTicketLine(null, "D1", null));
		results.add(connector.updateTicketStatus("D1", true));
		results.add(connector.updateTicketStatus("D1", true, true, "MF"));
		results.add(connector.updateTicketStatus("D1", true, true, "MF", "N2"));
		results.add(connector.pushPaymentHeader(null));
		results.add(connector.pushPaymentLine("P1", null));
		results.add(connector.pushReturnHeader(null));
		results.add(connector.pushReturnLine(null, "R1", null));
		results.add(connector.updateReturnStatus("R1", true));
		results.add(connector.pushSession(null));
		for (ErpOperationResult result : results) {
			assertFalse(result.isSuccess());
			assertEquals("The navpospages ERP connector is read only: it sends nothing to the ERP.", result.getMessage());
		}
		assertTrue(NavPosPagesConnector.class.isAnnotationPresent(Primary.class));
	}

	@Test
	@DisplayName("Nothing enabled: NoOpErpConnector, none of the navpospages beans")
	void nothingEnabled() {
		context().run(ctx -> {
			assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
			assertSame(ctx.getBean(NoOpErpConnector.class), connectorOf(ctx.getBean(ErpSynchronizationManager.class)));
			assertEquals(0, ctx.getBeanNamesForType(NavPosPagesConnector.class).length);
			assertEquals(0, ctx.getBeanNamesForType(NavPosPagesProperties.class).length);
			assertFalse(ctx.containsBean(NavPosPagesConfig.REST_TEMPLATE));
		});
	}

	@Test
	@DisplayName("Dynamics NAV enabled: DynamicsNavConnector, none of the navpospages beans")
	void dynamicsNav() {
		context().withPropertyValues(dynamicsNavKeys()).run(ctx -> {
					assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
					assertSame(ctx.getBean(DynamicsNavConnector.class),
							connectorOf(ctx.getBean(ErpSynchronizationManager.class)));
					assertEquals(0, ctx.getBeanNamesForType(NoOpErpConnector.class).length);
					assertEquals(0, ctx.getBeanNamesForType(NavPosPagesConnector.class).length);
				});
	}

	@Test
	@DisplayName("navpospages enabled: NavPosPagesConnector injected in ErpSynchronizationManager (it wins over NoOp)")
	void navPosPages() {
		context().withPropertyValues(navPosPagesKeys()).run(ctx -> {
			assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
			ErpConnector injected = connectorOf(ctx.getBean(ErpSynchronizationManager.class));
			assertSame(ctx.getBean(NavPosPagesConnector.class), injected);
			assertNotNull(ctx.getBean(NoOpErpConnector.class), "NoOp is still there, unchanged");
			assertEquals(0, ctx.getBeanNamesForType(DynamicsNavConnector.class).length);
			NavPosPagesProperties properties = ctx.getBean(NavPosPagesProperties.class);
			assertEquals("FRANCHISE", properties.getLocationCode());
			assertEquals(Integer.valueOf(19), properties.getDefaultVat());
			assertEquals(Boolean.TRUE, properties.getPriceIncludesVat());
			assertEquals(1000, properties.getBarcodePageSize());
			assertEquals("PointStockPOS", properties.getPage().getItems());
			assertNotNull(ctx.getBean(NavPosPagesReader.class));
		});
	}

	@Test
	@DisplayName("A refusal stops the context with its message, before the settings are bound (default-vat=abc)")
	void refusalStopsTheStartup() {
		context().withPropertyValues(navPosPagesKeys()).withPropertyValues("erp.navpospages.default-vat=abc").run(ctx -> {
			Throwable failure = ctx.getStartupFailure();
			assertNotNull(failure);
			assertTrue(rootMessage(failure).startsWith("Invalid value 'abc' for property erp.navpospages.default-vat"),
					rootMessage(failure));
		});
		context().withPropertyValues(navPosPagesKeys()).withPropertyValues(dynamicsNavKeys()).run(ctx -> {
			assertTrue(rootMessage(ctx.getStartupFailure()).startsWith("Invalid combination: erp.navpospages.enabled=true"),
					rootMessage(ctx.getStartupFailure()));
		});
	}

	private static ErpConnector connectorOf(ErpSynchronizationManager manager) {
		return (ErpConnector) ReflectionTestUtils.getField(manager, "erpConnector");
	}

	private static String rootMessage(Throwable failure) {
		Throwable cause = failure;
		while (cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getMessage();
	}
}
