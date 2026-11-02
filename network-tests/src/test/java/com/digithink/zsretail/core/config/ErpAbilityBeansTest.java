package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.core.env.PropertyResolver;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.headoffice.controller.HoStockPointAPI;
import com.digithink.zsretail.headoffice.service.HoStockPointRows;
import com.digithink.zsretail.headoffice.service.HoStockPointService;
import com.digithink.zsretail.support.Installations;

/**
 * Code cleanup, step 2, part 2: the stock point beans exist by the ability HEAD_OFFICE_STOCK_POINTS (on a head office
 * whose catalogue only comes from the ERP) where they existed by erp.navpospages.enabled=true: every file of configs/,
 * the six shapes of task 9.3, and the head office with the catalogue only from the ERP with and without the connector.
 * Code cleanup step 8: the connector is chosen by erp.connector only (the old line is refused at startup), so the old
 * conditions read as the connector in effect.
 */
class ErpAbilityBeansTest {

	/** 3.1.1: HoStockPointItemsService (the retired page "Items by point de stock") is gone. */
	static final List<Class<?>> STOCK_POINT_BEANS = Arrays.asList(HoStockPointAPI.class, HoStockPointService.class,
			HoStockPointRows.class);

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	/** The old conditions: @ConditionalOnInstallation(HEAD_OFFICE_ERP_CATALOGUE_ONLY) and the connector happyness in effect. */
	private static boolean before(PropertyResolver env) {
		return NodeOwnership.isErpCatalogueOnlySet(env)
				&& "happyness".equals(ErpConnectorEnvironmentPostProcessor.inEffect(env));
	}

	private static MockEnvironment catalogueOnlyHeadOffice(String... lines) {
		MockEnvironment env = Installations.type("headoffice");
		env.setProperty("ownership.catalogue", "ERP");
		for (String line : lines) {
			int eq = line.indexOf('=');
			env.setProperty(line.substring(0, eq), line.substring(eq + 1));
		}
		return env;
	}

	private static MockEnvironment chosen(MockEnvironment env, String connector) {
		env.setProperty(ErpConnectorEnvironmentPostProcessor.CONNECTOR_KEY, connector);
		return env;
	}

	@Test
	@DisplayName("Same stock point beans as before in every configuration")
	void sameBeans() {
		Map<String, MockEnvironment> environments = new LinkedHashMap<>();
		// The ZEN head office (step 10) came after the old conditions: its beans are checked in stockPointBeansWithZen
		Installations.machineFiles().stream().filter(file -> !file.equals(Installations.ZEN_HEAD_OFFICE))
				.forEach(file -> environments.put(file, Installations.machine(file)));
		Installations.configFiles().forEach(file -> environments.put(file, Installations.config(file)));
		Installations.variants().forEach(variant -> environments.put(variant, Installations.preset(variant)));
		environments.put("catalogue only, no connector", catalogueOnlyHeadOffice());
		environments.put("catalogue only, erp.connector= Happyness ", catalogueOnlyHeadOffice("erp.connector= Happyness "));
		environments.put("catalogue only, erp.connector=happyness",
				chosen(catalogueOnlyHeadOffice(), "happyness"));
		environments.put("catalogue only, erp.connector=none", chosen(catalogueOnlyHeadOffice(), "none"));
		environments.put("store, erp.connector=happyness", chosen(Installations.type("store"), "happyness"));
		environments.put("head office without an ERP, erp.connector=happyness",
				chosen(Installations.type("headoffice"), "happyness"));
		List<String> withBeans = new ArrayList<>();
		for (Map.Entry<String, MockEnvironment> entry : environments.entrySet()) {
			boolean expected = before(entry.getValue());
			if (expected) {
				withBeans.add(entry.getKey());
			}
			for (Class<?> bean : STOCK_POINT_BEANS) {
				assertEquals(expected, registered(entry.getValue(), bean), entry.getKey() + " " + bean.getSimpleName());
			}
		}
		assertTrue(withBeans.contains("catalogue only, erp.connector=happyness"), withBeans.toString());
		assertTrue(withBeans.contains("catalogue only, erp.connector= Happyness "), withBeans.toString());
		assertTrue(withBeans.contains(Installations.HAPPYNESS_HEAD_OFFICE), "the real Happyness head office: " + withBeans);
		assertEquals(3, withBeans.size(), withBeans.toString());
	}

	@Test
	@DisplayName("Item variants (ZEN), step 8: the stock point beans with erp.connector=zen, on a head office whose catalogue"
			+ " only comes from the ERP only")
	void stockPointBeansWithZen() {
		Map<String, MockEnvironment> environments = new LinkedHashMap<>();
		environments.put("catalogue only, erp.connector=zen", chosen(catalogueOnlyHeadOffice(), "zen"));
		environments.put("catalogue only, erp.connector= ZEN ", catalogueOnlyHeadOffice("erp.connector= ZEN "));
		environments.put("store, erp.connector=zen", chosen(Installations.type("store"), "zen"));
		environments.put("head office without an ERP, erp.connector=zen", chosen(Installations.type("headoffice"), "zen"));
		// Step 10: the ZEN head office of configs/customers
		environments.put("catalogue only, " + Installations.ZEN_HEAD_OFFICE, Installations.machine(Installations.ZEN_HEAD_OFFICE));
		for (Map.Entry<String, MockEnvironment> entry : environments.entrySet()) {
			boolean expected = entry.getKey().startsWith("catalogue only");
			for (Class<?> bean : STOCK_POINT_BEANS) {
				assertEquals(expected, registered(entry.getValue(), bean), entry.getKey() + " " + bean.getSimpleName());
			}
		}
	}

	/** Every configuration of the repository and the six shapes, plus the connector chosen each way. */
	private static Map<String, MockEnvironment> configurations() {
		Map<String, MockEnvironment> environments = new LinkedHashMap<>();
		Installations.machineFiles().forEach(file -> environments.put(file, Installations.machine(file)));
		Installations.configFiles().forEach(file -> environments.put(file, Installations.config(file)));
		Installations.variants().forEach(variant -> environments.put(variant, Installations.preset(variant)));
		for (String connector : new String[] { "none", "emtop", "happyness", "parafendri", "zen" }) {
			environments.put("store, erp.connector=" + connector, chosen(Installations.type("store"), connector));
			environments.put("catalogue only, erp.connector=" + connector,
					chosen(catalogueOnlyHeadOffice(), connector));
		}
		return environments;
	}

	@Test
	@DisplayName("3.1.1: GET /config headOfficeStockPoints is true exactly where the Stock points API exists (emtop on a catalogue-only head office: false)")
	void headOfficeStockPointsFlag() {
		List<String> withPoints = new ArrayList<>();
		for (Map.Entry<String, MockEnvironment> entry : configurations().entrySet()) {
			boolean api = registered(entry.getValue(), HoStockPointAPI.class);
			ApplicationModeService mode;
			try {
				mode = TestModes.of(entry.getValue());
			} catch (IllegalStateException refused) {
				// A shape that stops the startup (e.g. a catalogue from the head office without headoffice.url): no /config
				continue;
			}
			assertEquals(api, mode.isHeadOfficeStockPoints(), entry.getKey());
			if (api) {
				withPoints.add(entry.getKey());
			}
		}
		assertTrue(withPoints.containsAll(Arrays.asList("catalogue only, erp.connector=happyness",
				"catalogue only, erp.connector=parafendri", "catalogue only, erp.connector=zen")), withPoints.toString());
		assertTrue(!withPoints.contains("catalogue only, erp.connector=emtop"), withPoints.toString());
	}

	@Test
	@DisplayName("Part 5: exactly one ErpConnector bean, the one the old conditions gave (NoOp no longer beside navpospages)")
	void oneConnector() {
		List<Class<?>> connectors = Arrays.asList(com.digithink.zsretail.erp.api.NoOpErpConnector.class,
				com.digithink.zsretail.connector.emtop.connector.DynamicsNavConnector.class,
				com.digithink.zsretail.connector.happyness.connector.NavPosPagesConnector.class,
				com.digithink.zsretail.connector.parafendri.connector.ParafendriConnector.class,
				com.digithink.zsretail.connector.zen.connector.ZenConnector.class);
		for (Map.Entry<String, MockEnvironment> entry : configurations().entrySet()) {
			MockEnvironment env = entry.getValue();
			String inEffect = ErpConnectorEnvironmentPostProcessor.inEffect(env);
			// The old conditions, by the connector in effect: DynamicsNavConnector for emtop, NavPosPagesConnector for
			// happyness, NoOp for none; item variants (ZEN), step 8: ZenConnector for zen; ParaFendri, step 0:
			// ParafendriConnector for parafendri
			Class<?> expected = connectors
					.get(Arrays.asList("none", "emtop", "happyness", "parafendri", "zen").indexOf(inEffect));
			List<Class<?>> present = new ArrayList<>();
			for (Class<?> connector : connectors) {
				if (registered(env, connector)) {
					present.add(connector);
				}
			}
			assertEquals(Arrays.asList(expected), present, entry.getKey());
		}
	}

	@Test
	@DisplayName("Step 3: the engine, its job, state, head office reads, import and images exist with a connector that keeps"
			+ " points de stock, on a head office whose catalogue only comes from the ERP only (ParaFendri: never on a store)")
	void engineBeans() {
		List<String> withBeans = new ArrayList<>();
		for (Map.Entry<String, MockEnvironment> entry : configurations().entrySet()) {
			String inEffect = ErpConnectorEnvironmentPostProcessor.inEffect(entry.getValue());
			boolean expected = ("happyness".equals(inEffect) || "zen".equals(inEffect) || "parafendri".equals(inEffect))
					&& NodeOwnership.isErpCatalogueOnlySet(entry.getValue());
			if (expected) {
				withBeans.add(entry.getKey());
			}
			for (Class<?> bean : ENGINE_BEANS) {
				assertEquals(expected, registered(entry.getValue(), bean), entry.getKey() + " " + bean.getSimpleName());
			}
		}
		assertTrue(withBeans.contains("catalogue only, erp.connector=happyness"), withBeans.toString());
		assertTrue(withBeans.contains(Installations.HAPPYNESS_HEAD_OFFICE), withBeans.toString());
		assertTrue(withBeans.contains("catalogue only, erp.connector=zen"), withBeans.toString());
		assertTrue(withBeans.contains(Installations.ZEN_HEAD_OFFICE), withBeans.toString());
		assertTrue(withBeans.contains("catalogue only, erp.connector=parafendri"), withBeans.toString());
		assertEquals(5, withBeans.size(), withBeans.toString());
	}

	/** The catalogue engine's seven beans in core (ability HEAD_OFFICE_STOCK_POINTS and the installation). */
	static final List<Class<?>> ENGINE_BEANS = Arrays.asList(com.digithink.zsretail.core.erp.engine.JdbcCatalogueState.class,
			com.digithink.zsretail.core.erp.engine.JpaHeadOfficeCatalogue.class,
			com.digithink.zsretail.core.erp.engine.JpaHeadOfficeImages.class,
			com.digithink.zsretail.core.erp.engine.BootstrapCatalogueImport.class,
			com.digithink.zsretail.core.erp.engine.CatalogueEngine.class,
			com.digithink.zsretail.core.erp.engine.CatalogueJob.class,
			com.digithink.zsretail.core.erp.engine.ItemImagesEngine.class);

	@Test
	@DisplayName("ParaFendri, the first connector on stores and a head office: its store (configs/customers) keeps the"
			+ " connector and no engine, catalogue source or stock point bean; its head office whose catalogue comes from"
			+ " the ERP has them all")
	void parafendriStoreAndHeadOffice() {
		List<Class<?>> source = Arrays.asList(
				com.digithink.zsretail.connector.parafendri.client.ParafendriCatalogueSource.class,
				com.digithink.zsretail.connector.parafendri.config.ParafendriCatalogueSettings.class);
		Map<String, MockEnvironment> stores = new LinkedHashMap<>();
		stores.put("customers/parafendri-store.properties", Installations.machine("customers/parafendri-store.properties"));
		stores.put("store, erp.connector=parafendri", chosen(Installations.type("store"), "parafendri"));
		stores.put("head office without an ERP, erp.connector=parafendri",
				chosen(Installations.type("headoffice"), "parafendri"));
		for (Map.Entry<String, MockEnvironment> entry : stores.entrySet()) {
			assertTrue(registered(entry.getValue(),
					com.digithink.zsretail.connector.parafendri.connector.ParafendriConnector.class), entry.getKey());
			for (List<Class<?>> beans : Arrays.asList(ENGINE_BEANS, STOCK_POINT_BEANS, source)) {
				for (Class<?> bean : beans) {
					assertTrue(!registered(entry.getValue(), bean), entry.getKey() + " " + bean.getSimpleName());
				}
			}
		}
		MockEnvironment headOffice = chosen(catalogueOnlyHeadOffice(), "parafendri");
		for (List<Class<?>> beans : Arrays.asList(ENGINE_BEANS, STOCK_POINT_BEANS, source)) {
			for (Class<?> bean : beans) {
				assertTrue(registered(headOffice, bean), "catalogue only " + bean.getSimpleName());
			}
		}
	}

	@Test
	@DisplayName("headoffice.supply.source=ERP: accepted with the ability SUPPLY_INVOICES, by erp.connector")
	void supplySource() {
		for (MockEnvironment env : Arrays.asList(catalogueOnlyHeadOffice("erp.connector= HAPPYNESS "),
				chosen(catalogueOnlyHeadOffice(), "happyness"), chosen(catalogueOnlyHeadOffice(), "zen"))) {
			env.setProperty("headoffice.supply.source", "ERP");
			assertTrue(NodeOwnership.resolve(env).isErpCatalogueOnly());
			assertTrue(NodeOwnership.isSupplyFromErpSourceSet(env));
		}
		// Item variants (ZEN), step 9: zen reads the supply invoices (accepted above); the others refused
		for (MockEnvironment env : Arrays.asList(catalogueOnlyHeadOffice(),
				chosen(catalogueOnlyHeadOffice(), "none"), chosen(catalogueOnlyHeadOffice(), "emtop"))) {
			env.setProperty("headoffice.supply.source", "ERP");
			String message = org.junit.jupiter.api.Assertions
					.assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(env)).getMessage();
			assertTrue(message.contains("erp.connector=happyness or erp.connector=zen. Choose it"), message);
		}
	}
}
