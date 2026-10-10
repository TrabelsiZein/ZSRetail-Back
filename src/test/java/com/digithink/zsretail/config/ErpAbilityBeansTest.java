package com.digithink.zsretail.config;

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

import com.digithink.zsretail.headoffice.controller.HoStockPointAPI;
import com.digithink.zsretail.headoffice.service.HoStockPointItemsService;
import com.digithink.zsretail.headoffice.service.HoStockPointRows;
import com.digithink.zsretail.headoffice.service.HoStockPointService;
import com.digithink.zsretail.support.Installations;

/**
 * Code cleanup, step 2, part 2: the stock point beans exist by the ability CATALOGUE_BY_STOCK_POINTS (on a head office
 * whose catalogue only comes from the ERP) where they existed by erp.navpospages.enabled=true: every file of deploy/ and
 * configs/, the six shapes of task 9.3, and the head office with the catalogue only from the ERP with and without the
 * connector, chosen by the old line or by erp.connector (its translation).
 */
class ErpAbilityBeansTest {

	static final List<Class<?>> STOCK_POINT_BEANS = Arrays.asList(HoStockPointAPI.class, HoStockPointService.class,
			HoStockPointRows.class, HoStockPointItemsService.class);

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	/** The old conditions: @ConditionalOnHeadOfficeErpCatalogue and @ConditionalOnProperty(erp.navpospages.enabled=true). */
	private static boolean before(PropertyResolver env) {
		return NodeOwnership.isErpCatalogueOnlySet(env)
				&& "true".equalsIgnoreCase(env.getProperty("erp.navpospages.enabled"));
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

	private static MockEnvironment translated(MockEnvironment env, String connector) {
		ErpConnectorEnvironmentPostProcessor.translate(connector).forEach((key, value) -> env.setProperty(key,
				String.valueOf(value)));
		return env;
	}

	@Test
	@DisplayName("Same stock point beans as before in every configuration")
	void sameBeans() {
		Map<String, MockEnvironment> environments = new LinkedHashMap<>();
		Installations.machineFiles().forEach(file -> environments.put(file, Installations.machine(file)));
		Installations.configFiles().forEach(file -> environments.put(file, Installations.config(file)));
		Installations.variants().forEach(variant -> environments.put(variant, Installations.preset(variant)));
		environments.put("catalogue only, no connector", catalogueOnlyHeadOffice());
		environments.put("catalogue only, old line", catalogueOnlyHeadOffice("erp.navpospages.enabled=true"));
		environments.put("catalogue only, old line TRUE", catalogueOnlyHeadOffice("erp.navpospages.enabled=TRUE"));
		environments.put("catalogue only, old line false", catalogueOnlyHeadOffice("erp.navpospages.enabled=false"));
		environments.put("catalogue only, erp.connector=navpospages",
				translated(catalogueOnlyHeadOffice(), "navpospages"));
		environments.put("catalogue only, erp.connector=none", translated(catalogueOnlyHeadOffice(), "none"));
		environments.put("store, old line", translated(Installations.type("store"), "navpospages"));
		environments.put("head office without an ERP, old line",
				translated(Installations.type("headoffice"), "navpospages"));
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
		assertTrue(withBeans.contains("catalogue only, erp.connector=navpospages"), withBeans.toString());
		assertTrue(withBeans.contains("catalogue only, old line TRUE"), withBeans.toString());
		assertTrue(withBeans.size() >= 4, "the real Happyness head office among them: " + withBeans);
	}

	@Test
	@DisplayName("headoffice.supply.source=ERP: accepted with the ability SUPPLY_INVOICES, by the old line or erp.connector")
	void supplySource() {
		for (MockEnvironment env : Arrays.asList(catalogueOnlyHeadOffice("erp.navpospages.enabled= true "),
				translated(catalogueOnlyHeadOffice(), "navpospages"))) {
			env.setProperty("headoffice.supply.source", "ERP");
			assertTrue(NodeOwnership.resolve(env).isErpCatalogueOnly());
			assertTrue(NodeOwnership.isSupplyFromErpSourceSet(env));
		}
		for (MockEnvironment env : Arrays.asList(catalogueOnlyHeadOffice(),
				translated(catalogueOnlyHeadOffice(), "none"), translated(catalogueOnlyHeadOffice(), "dynamicsnav"))) {
			env.setProperty("headoffice.supply.source", "ERP");
			String message = org.junit.jupiter.api.Assertions
					.assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(env)).getMessage();
			assertTrue(message.contains("erp.connector=navpospages (or the old line erp.navpospages.enabled=true, still"
					+ " accepted)"), message);
		}
	}
}
