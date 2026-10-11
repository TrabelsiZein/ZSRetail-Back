package com.digithink.zsretail.connector.happyness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;

import javax.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.digithink.zsretail.connector.emtop.client.DynamicsNavRestClient;
import com.digithink.zsretail.connector.emtop.config.DynamicsNavConfig;
import com.digithink.zsretail.connector.emtop.config.DynamicsNavProperties;
import com.digithink.zsretail.connector.emtop.connector.DynamicsNavConnector;
import com.digithink.zsretail.connector.emtop.mapper.DynamicsNavMapper;
import com.digithink.zsretail.connector.happyness.client.NavPosPagesCatalogueSource;
import com.digithink.zsretail.connector.happyness.client.NavPosPagesRestClient;
import com.digithink.zsretail.connector.happyness.config.NavPosPagesCatalogueSettings;
import com.digithink.zsretail.connector.happyness.config.NavPosPagesConfig;
import com.digithink.zsretail.connector.happyness.config.NavPosPagesProperties;
import com.digithink.zsretail.connector.happyness.config.NavPosPagesStartupCheck;
import com.digithink.zsretail.connector.happyness.connector.NavPosPagesConnector;
import com.digithink.zsretail.connector.happyness.reader.NavPosPagesReader;
import com.digithink.zsretail.connector.happyness.sync.NavPosPagesInvoices;
import com.digithink.zsretail.core.erp.engine.BootstrapCatalogueImport;
import com.digithink.zsretail.core.erp.engine.CatalogueEngine;
import com.digithink.zsretail.core.erp.engine.JdbcCatalogueState;
import com.digithink.zsretail.core.erp.engine.JpaHeadOfficeCatalogue;
import com.digithink.zsretail.core.erp.service.ErpCommunicationService;
import com.digithink.zsretail.core.erp.service.ErpItemBootstrapService;
import com.digithink.zsretail.core.erp.service.ErpSynchronizationManager;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.service.LocationService;
import com.digithink.zsretail.erp.api.ErpConnector;
import com.digithink.zsretail.erp.api.ErpStoreLocation;
import com.digithink.zsretail.erp.api.NoOpErpConnector;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.headoffice.repository.HoStockPointItemRepository;
import com.digithink.zsretail.headoffice.repository.HoStockPointRepository;
import com.digithink.zsretail.headoffice.service.HoStockPointRows;

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
						NavPosPagesConnector.class, JdbcCatalogueState.class, JpaHeadOfficeCatalogue.class,
						CatalogueEngine.class, NavPosPagesCatalogueSource.class, NavPosPagesCatalogueSettings.class,
						NavPosPagesInvoices.class, HoStockPointRows.class, BootstrapCatalogueImport.class,
						ErpSynchronizationManager.class)
				// Plain singletons: never called here, and not autowired
				.withInitializer(ctx -> {
					ctx.getBeanFactory().registerSingleton("generalSetupService", mock(GeneralSetupService.class));
					ctx.getBeanFactory().registerSingleton("locationService", mock(LocationService.class));
					ctx.getBeanFactory().registerSingleton("erpStoreLocationService", mock(ErpStoreLocation.class));
					ctx.getBeanFactory().registerSingleton("erpCommunicationService", mock(ErpCommunicationService.class));
					ctx.getBeanFactory().registerSingleton("jdbcTemplate", mock(JdbcTemplate.class));
					ctx.getBeanFactory().registerSingleton("entityManager", mock(EntityManager.class));
					ctx.getBeanFactory().registerSingleton("erpItemBootstrapService", mock(ErpItemBootstrapService.class));
					ctx.getBeanFactory().registerSingleton("hoStockPointRepository", mock(HoStockPointRepository.class));
					ctx.getBeanFactory().registerSingleton("hoStockPointItemRepository",
							mock(HoStockPointItemRepository.class));
				});
	}

	private static String[] dynamicsNavKeys() {
		return new String[] { "erp.connector=emtop", "erp.emtop.base-url=http://nav.test:7048/NAV/ODataV4",
				"erp.emtop.company=TEST", "erp.emtop.domain=D", "erp.emtop.username=u",
				"erp.emtop.password=p" };
	}

	private static String[] navPosPagesKeys() {
		return new String[] { "node.type=HEAD_OFFICE", "ownership.catalogue=ERP", "erp.connector=happyness",
				"erp.happyness.base-url=" + NavPosPagesTestSupport.BASE_URL, "erp.happyness.company=HAPPYNESS",
				"erp.happyness.username=user", "erp.happyness.password=secret",
				"erp.happyness.location-code=FRANCHISE" /* release 2.2: a leftover line, ignored */, "erp.happyness.default-vat=19",
				"erp.happyness.price-includes-vat=true" };
	}

	@Test
	@DisplayName("Every fetch but the catalogue answers an empty list, every push or update a read-only failure")
	void readOnly() {
		// Step 6: the four catalogue fetches hand the changes (NavPosPagesSyncTest); every other fetch is empty
		NavPosPagesConnector connector = new NavPosPagesConnector(null);
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
		assertFalse(NavPosPagesConnector.class.isAnnotationPresent(Primary.class), "code cleanup step 2: no @Primary");
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
	@DisplayName("erp.connector=happyness: NavPosPagesConnector injected in ErpSynchronizationManager (the only connector, no NoOp)")
	void navPosPages() {
		context().withPropertyValues(navPosPagesKeys()).run(ctx -> {
			assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
			ErpConnector injected = connectorOf(ctx.getBean(ErpSynchronizationManager.class));
			assertSame(ctx.getBean(NavPosPagesConnector.class), injected);
			assertEquals(0, ctx.getBeanNamesForType(NoOpErpConnector.class).length, "code cleanup step 2: no NoOp beside it");
			assertEquals(0, ctx.getBeanNamesForType(DynamicsNavConnector.class).length);
			NavPosPagesProperties properties = ctx.getBean(NavPosPagesProperties.class);
			assertEquals(Integer.valueOf(19), properties.getDefaultVat());
			assertEquals(Boolean.TRUE, properties.getPriceIncludesVat());
			assertEquals(1000, properties.getBarcodePageSize());
			assertEquals("PointStockPOS", properties.getPage().getItems());
			assertNotNull(ctx.getBean(NavPosPagesReader.class));
			assertNotNull(ctx.getBean(BootstrapCatalogueImport.class), "the items run applies its packets through it");
			assertEquals(500, properties.getPacketSize());
			// Invoices from the ERP, step (a): the defaults, no year
			assertEquals("FactureFranchise", properties.getPage().getInvoices());
			assertEquals("FactureFranchiseSalesInvLines", properties.getInvoices().getLinesExpand());
			assertEquals("Sell_to_Customer_No", properties.getInvoices().getCustomerField());
			assertEquals("FVV", properties.getInvoices().getNumberPrefix());
			assertEquals(50, properties.getInvoices().getMaxPerRun());
		});
	}

	@Test
	@DisplayName("Invoices from the ERP: the keys bound, the year prefix; release 2.2: years and start-number ignored, the start still works")
	void invoiceKeysBound() {
		context().withPropertyValues(navPosPagesKeys())
				.withPropertyValues("erp.happyness.invoices.years= 2025 , 2026", "erp.happyness.invoices.number-prefix=FA",
						"erp.happyness.invoices.start-number=FA25000000999", "erp.happyness.invoices.max-per-run=20",
						"erp.happyness.invoices.customer-field=Sell_to_Customer_Name")
				.run(ctx -> {
					assertTrue(ctx.getStartupFailure() == null, String.valueOf(ctx.getStartupFailure()));
					NavPosPagesProperties.Invoices invoices = ctx.getBean(NavPosPagesProperties.class).getInvoices();
					assertEquals("FA26", invoices.yearPrefix(2026));
					assertEquals(Integer.valueOf(2025), invoices.yearOf("FA25000000999"));
					assertEquals(20, invoices.getMaxPerRun());
					assertEquals("Sell_to_Customer_Name", invoices.getCustomerField());
				});
	}

	@Test
	@DisplayName("Code cleanup step 2: the seller name of the stores' invoices comes through the connector, as the key gives it")
	void sellerName() {
		context().withPropertyValues(navPosPagesKeys()).run(ctx -> assertEquals("Head office",
				ctx.getBean(ErpSynchronizationManager.class).supplyInvoiceSellerName(), "the default"));
		context().withPropertyValues(navPosPagesKeys())
				.withPropertyValues("erp.happyness.invoices.seller-name=Happyness Siège")
				.run(ctx -> assertEquals("Happyness Siège",
						ctx.getBean(ErpSynchronizationManager.class).supplyInvoiceSellerName(), "as written"));
		context().run(ctx -> assertEquals(null,
				ctx.getBean(ErpSynchronizationManager.class).supplyInvoiceSellerName(), "NoOp: no invoices"));
	}

	@Test
	@DisplayName("A refusal stops the context with its message, before the settings are bound (default-vat=abc)")
	void refusalStopsTheStartup() {
		context().withPropertyValues(navPosPagesKeys()).withPropertyValues("erp.happyness.default-vat=abc").run(ctx -> {
			Throwable failure = ctx.getStartupFailure();
			assertNotNull(failure);
			assertTrue(rootMessage(failure).startsWith("Invalid value 'abc' for property erp.happyness.default-vat"),
					rootMessage(failure));
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
