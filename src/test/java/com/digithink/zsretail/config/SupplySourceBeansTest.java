package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.headoffice.controller.HeadOfficeSupplyAPI;
import com.digithink.zsretail.headoffice.controller.HoDeliveryAPI;
import com.digithink.zsretail.headoffice.controller.HoErpInvoiceAPI;
import com.digithink.zsretail.headoffice.controller.HoNetworkStockAPI;
import com.digithink.zsretail.headoffice.controller.HoPriceListAPI;
import com.digithink.zsretail.headoffice.controller.HoSupplyInvoiceAPI;
import com.digithink.zsretail.headoffice.controller.HoSupplyPriceAPI;
import com.digithink.zsretail.headoffice.service.DownDomainProvider;
import com.digithink.zsretail.headoffice.service.HoCatalogueService;
import com.digithink.zsretail.headoffice.service.HoDeliveryService;
import com.digithink.zsretail.headoffice.service.HoErpInvoiceService;
import com.digithink.zsretail.headoffice.service.HoNetworkStockService;
import com.digithink.zsretail.headoffice.service.HoPriceListService;
import com.digithink.zsretail.headoffice.service.HoSupplyInvoiceService;
import com.digithink.zsretail.headoffice.service.HoSupplyPriceService;
import com.digithink.zsretail.headoffice.service.SupplyConfirmationReceiver;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.support.Installations;

/**
 * Invoices from the ERP, step (b): the two conditions on the real files (no context). @ConditionalOnHeadOfficeOwnSupply
 * keeps the BLs, supply prices and supply invoices on a head office without an ERP and on a catalogue-only head office;
 * @ConditionalOnHeadOfficeErpSupply puts the ERP invoices on a catalogue-only head office with
 * headoffice.supply.source=ERP. The catalogue feed, the selling price lists, the network stock and HeadOfficeSupplyAPI
 * keep @ConditionalOnHeadOfficeWithoutErp. Every DownDomainProvider of the code base is scanned: in every mode there is
 * exactly one of the domain SUPPLY where a head office supplies its stores, none on a store or on a head office whose ERP
 * owns all three (as before), and exactly one confirmation receiver beside HeadOfficeSupplyAPI.
 */
class SupplySourceBeansTest {

	static final List<Class<?>> OWN_SUPPLY_BEANS = Arrays.asList(HoDeliveryService.class, HoDeliveryAPI.class,
			HoSupplyInvoiceService.class, HoSupplyInvoiceAPI.class, HoSupplyPriceService.class, HoSupplyPriceAPI.class);

	static final List<Class<?>> ERP_SUPPLY_BEANS = Arrays.asList(HoErpInvoiceService.class, HoErpInvoiceAPI.class);

	static final List<Class<?>> KEPT_WITHOUT_ERP_BEANS = Arrays.asList(HoCatalogueService.class, HoPriceListService.class,
			HoPriceListAPI.class, HoNetworkStockService.class, HoNetworkStockAPI.class, HeadOfficeSupplyAPI.class);

	private static MockEnvironment catalogueOnly() {
		MockEnvironment env = Installations.type("headoffice");
		env.setProperty("ownership.catalogue", "ERP");
		return env;
	}

	private static MockEnvironment erpSupply() {
		MockEnvironment env = catalogueOnly();
		env.setProperty("headoffice.supply.source", "ERP");
		env.setProperty("erp.navpospages.enabled", "true");
		return env;
	}

	/** The modes, by name. */
	private static Map<String, MockEnvironment> modes() {
		Map<String, MockEnvironment> modes = new LinkedHashMap<>();
		modes.put("store", Installations.type("store"));
		modes.put("store with the ERP", Installations.preset("store-erp"));
		modes.put("head office without an ERP", Installations.type("headoffice"));
		modes.put("head office whose ERP owns all three", Installations.machine("dev/headoffice-erp.properties"));
		modes.put("head office, catalogue only from the ERP", catalogueOnly());
		modes.put("head office, catalogue only from the ERP, supply source ERP", erpSupply());
		modes.put("Happyness head office (configs/local)", Installations.config(ModeQuestionTruthTableTest.HAPPYNESS_HEAD_OFFICE));
		return modes;
	}

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	/** Every @Service of the application that is a DownDomainProvider, whatever its condition. */
	private static List<Class<?>> providers() throws ClassNotFoundException {
		// The scanner would evaluate the @Conditional of each class on a default environment: matched on @Service alone
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(MetadataReader reader) {
				return reader.getAnnotationMetadata().hasAnnotation(Service.class.getName());
			}
		};
		scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));
		List<Class<?>> providers = new ArrayList<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents("com.digithink.zsretail")) {
			Class<?> type = Class.forName(candidate.getBeanClassName());
			if (DownDomainProvider.class.isAssignableFrom(type)) {
				providers.add(type);
			}
		}
		return providers;
	}

	/** The domain of a provider class, from its real getDomain() on an instance built without its constructor. */
	private static DataDomain domainOf(Class<?> provider) {
		return ((DownDomainProvider) mock(provider, CALLS_REAL_METHODS)).getDomain();
	}

	@Test
	@DisplayName("Exactly one SUPPLY provider where a head office supplies its stores; none on a store or a head office with all three in the ERP")
	void oneSupplyProviderPerMode() throws Exception {
		List<Class<?>> supplyProviders = providers().stream().filter(p -> domainOf(p) == DataDomain.SUPPLY)
				.collect(Collectors.toList());
		assertTrue(supplyProviders.containsAll(Arrays.asList(HoDeliveryService.class, HoErpInvoiceService.class)),
				supplyProviders.toString());
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("store", "[]");
		expected.put("store with the ERP", "[]");
		expected.put("head office without an ERP", "[HoDeliveryService]");
		expected.put("head office whose ERP owns all three", "[]");
		expected.put("head office, catalogue only from the ERP", "[HoDeliveryService]");
		expected.put("head office, catalogue only from the ERP, supply source ERP", "[HoErpInvoiceService]");
		expected.put("Happyness head office (configs/local)", "[HoErpInvoiceService]");
		for (Map.Entry<String, MockEnvironment> mode : modes().entrySet()) {
			List<String> present = supplyProviders.stream().filter(p -> registered(mode.getValue(), p))
					.map(Class::getSimpleName).collect(Collectors.toList());
			assertEquals(expected.get(mode.getKey()), present.toString(), mode.getKey());
		}
	}

	@Test
	@DisplayName("Each bean where its condition says; one confirmation receiver wherever HeadOfficeSupplyAPI exists")
	void beansPerMode() {
		for (Class<?> bean : OWN_SUPPLY_BEANS) {
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficeOwnSupply.class), bean.getSimpleName());
		}
		for (Class<?> bean : ERP_SUPPLY_BEANS) {
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficeErpSupply.class), bean.getSimpleName());
		}
		for (Class<?> bean : KEPT_WITHOUT_ERP_BEANS) {
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficeWithoutErp.class), bean.getSimpleName());
		}
		for (Map.Entry<String, MockEnvironment> mode : modes().entrySet()) {
			MockEnvironment env = mode.getValue();
			boolean withoutErp = NodeOwnership.isHeadOfficeWithoutErpSet(env);
			boolean erpSupply = NodeOwnership.isSupplyFromErpSourceSet(env);
			for (Class<?> bean : OWN_SUPPLY_BEANS) {
				assertEquals(withoutErp && !erpSupply, registered(env, bean), mode.getKey() + ": " + bean.getSimpleName());
			}
			for (Class<?> bean : ERP_SUPPLY_BEANS) {
				assertEquals(erpSupply, registered(env, bean), mode.getKey() + ": " + bean.getSimpleName());
			}
			for (Class<?> bean : KEPT_WITHOUT_ERP_BEANS) {
				assertEquals(withoutErp, registered(env, bean), mode.getKey() + ": " + bean.getSimpleName());
			}
			long receivers = Arrays.asList(HoDeliveryService.class, HoErpInvoiceService.class).stream()
					.filter(r -> registered(env, r)).count();
			assertEquals(registered(env, HeadOfficeSupplyAPI.class) ? 1 : 0, receivers, mode.getKey());
		}
		assertTrue(SupplyConfirmationReceiver.class.isAssignableFrom(HoDeliveryService.class));
		assertTrue(SupplyConfirmationReceiver.class.isAssignableFrom(HoErpInvoiceService.class));
		assertEquals(OnHeadOfficeOwnSupplyCondition.class,
				ConditionalOnHeadOfficeOwnSupply.class.getAnnotation(org.springframework.context.annotation.Conditional.class)
						.value()[0]);
		assertEquals(OnHeadOfficeErpSupplyCondition.class,
				ConditionalOnHeadOfficeErpSupply.class.getAnnotation(org.springframework.context.annotation.Conditional.class)
						.value()[0]);
		assertFalse(registered(erpSupply(), HoDeliveryService.class));
	}
}
