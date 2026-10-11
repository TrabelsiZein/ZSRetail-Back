package com.digithink.zsretail.erp.dynamicsnav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.erp.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.dynamicsnav.client.DynamicsNavRestClient;
import com.digithink.zsretail.erp.dynamicsnav.config.DynamicsNavProperties;
import com.digithink.zsretail.erp.dynamicsnav.dto.DynamicsNavCollectionResponse;
import com.digithink.zsretail.erp.spi.ErpStoreLocation;
import com.digithink.zsretail.erp.spi.ErpSyncWarningException;
import com.digithink.zsretail.model.Location;
import com.digithink.zsretail.service.ErpStoreLocationService;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.LocationService;
import com.digithink.zsretail.support.Installations;

/**
 * Code cleanup step 5.8d: the store values DynamicsNavRestClient puts in its NAV requests (General Setup
 * DEFAULT_LOCATION, the responsibility centre of the default location) are the same before and after it reads them
 * through erp-api ({@link ErpStoreLocation}, answered by {@link ErpStoreLocationService}). The URLs below were recorded
 * on the client of 8cf9919, before the change. No network: the RestTemplate is a mock. And the answer exists wherever
 * the client does, in every configuration of the installation beans record.
 */
class DynamicsNavRestClientValuesTest {

	private static final String BASE = "http://nav.test/ODataV4/Company('CRONUS')/";
	private static final OffsetDateTime AFTER = OffsetDateTime.of(2026, 10, 1, 8, 30, 0, 0, ZoneOffset.ofHours(1));

	private final List<String> urls = new ArrayList<>();

	@SuppressWarnings("unchecked")
	private DynamicsNavRestClient client(String defaultLocation, List<Location> defaultLocations) {
		RestTemplate rest = mock(RestTemplate.class);
		doAnswer(call -> {
			urls.add(call.getArgument(0));
			return ResponseEntity.ok(new DynamicsNavCollectionResponse<>());
		}).when(rest).exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class));
		DynamicsNavProperties properties = new DynamicsNavProperties();
		properties.setBaseUrl("http://nav.test/ODataV4");
		properties.setCompany("CRONUS");
		GeneralSetupService generalSetup = mock(GeneralSetupService.class);
		when(generalSetup.findValueByCode("DEFAULT_LOCATION")).thenReturn(defaultLocation);
		LocationService locations = mock(LocationService.class);
		when(locations.findByField("isDefault", "=", true)).thenReturn(defaultLocations);
		return new DynamicsNavRestClient(rest, properties, new ErpStoreLocationService(generalSetup, locations));
	}

	private static Location location(String responsibilityCenter) {
		Location location = new Location();
		location.setResponsibilityCenter(responsibilityCenter);
		return location;
	}

	private static ErpSyncFilter after() {
		ErpSyncFilter filter = new ErpSyncFilter();
		filter.setUpdatedAfter(AFTER);
		return filter;
	}

	private List<String> sent(DynamicsNavRestClient client) {
		urls.clear();
		client.fetchItems(null);
		client.fetchItems(after());
		client.fetchSalesPrices(null);
		client.fetchSalesPrices(after());
		client.fetchSalesDiscounts(null);
		client.fetchSalesDiscounts(after());
		return new ArrayList<>(urls);
	}

	@Test
	@DisplayName("The location code and the responsibility centre go into the same filters as before")
	void sameValuesSent() {
		assertEquals(Arrays.asList(BASE + "StockkeepingUnitList?$filter=Location_Code eq 'MAG''1'",
				BASE + "StockkeepingUnitList?$filter=Modified_At gt 2026-10-01T07:30:00Z and Location_Code eq 'MAG''1'",
				BASE + "SalesPrice?$filter=Responsibility_Center eq 'RC1' or Responsibility_Center eq ''",
				BASE + "SalesPrice?$filter=Modified_At gt 2026-10-01T07:30:00Z and (Responsibility_Center eq 'RC1' or"
						+ " Responsibility_Center eq '')",
				BASE + "SalesDiscount?$filter=Responsibility_Center eq 'RC1' or Responsibility_Center eq ''",
				BASE + "SalesDiscount?$filter=Modified_At gt 2026-10-01T07:30:00Z and (Responsibility_Center eq 'RC1' or"
						+ " Responsibility_Center eq '')"),
				sent(client("MAG'1", Arrays.asList(location("RC1"), location("RC2")))));
	}

	@Test
	@DisplayName("A default location without a responsibility centre is sent as before")
	void noResponsibilityCenter() {
		assertEquals(Arrays.asList(BASE + "StockkeepingUnitList?$filter=Location_Code eq 'MAG1'",
				BASE + "StockkeepingUnitList?$filter=Modified_At gt 2026-10-01T07:30:00Z and Location_Code eq 'MAG1'",
				BASE + "SalesPrice?$filter=Responsibility_Center eq 'null' or Responsibility_Center eq ''",
				BASE + "SalesPrice?$filter=Modified_At gt 2026-10-01T07:30:00Z and (Responsibility_Center eq 'null' or"
						+ " Responsibility_Center eq '')",
				BASE + "SalesDiscount?$filter=Responsibility_Center eq 'null' or Responsibility_Center eq ''",
				BASE + "SalesDiscount?$filter=Modified_At gt 2026-10-01T07:30:00Z and (Responsibility_Center eq 'null' or"
						+ " Responsibility_Center eq '')"),
				sent(client("MAG1", Collections.singletonList(location(null)))));
	}

	@Test
	@DisplayName("No DEFAULT_LOCATION, or no default location: the same warning, nothing sent")
	void notConfigured() {
		List<Consumer<DynamicsNavRestClient>> fetches = Arrays.asList(c -> c.fetchItems(null),
				c -> c.fetchSalesPrices(null), c -> c.fetchSalesDiscounts(null));
		urls.clear();
		for (String location : new String[] { null, "", "  " }) {
			DynamicsNavRestClient client = client(location, Collections.singletonList(location("RC1")));
			assertEquals("DEFAULT_LOCATION is not configured",
					assertThrows(ErpSyncWarningException.class, () -> fetches.get(0).accept(client)).getMessage());
		}
		DynamicsNavRestClient client = client("MAG1", Collections.emptyList());
		for (Consumer<DynamicsNavRestClient> fetch : fetches.subList(1, 3)) {
			assertEquals("DEFAULT_LOCATION is not configured",
					assertThrows(ErpSyncWarningException.class, () -> fetch.accept(client)).getMessage());
		}
		assertEquals(Collections.emptyList(), urls);
	}

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	@Test
	@DisplayName("Wherever the client exists, in every configuration of the installation beans record, the answer exists")
	void everyConfiguration() {
		Map<String, MockEnvironment> configurations = new LinkedHashMap<>();
		Installations.machineFiles().forEach(file -> configurations.put(file, Installations.machine(file)));
		Installations.configFiles().forEach(file -> configurations.put(file, Installations.config(file)));
		Installations.variants().forEach(variant -> configurations.put(variant, Installations.preset(variant)));
		assertTrue(configurations.size() > 20, configurations.keySet().toString());
		Set<String> withClient = new TreeSet<>();
		Set<String> missing = new TreeSet<>();
		for (Map.Entry<String, MockEnvironment> configuration : configurations.entrySet()) {
			if (registered(configuration.getValue(), DynamicsNavRestClient.class)) {
				withClient.add(configuration.getKey());
				if (!registered(configuration.getValue(), ErpStoreLocationService.class)) {
					missing.add(configuration.getKey());
				}
			}
		}
		assertTrue(!withClient.isEmpty(), "no configuration has the client");
		assertEquals(new TreeSet<String>(), missing);
		assertEquals(1, ErpStoreLocationService.class.getAnnotations().length, "only @Service: no condition");
	}
}
