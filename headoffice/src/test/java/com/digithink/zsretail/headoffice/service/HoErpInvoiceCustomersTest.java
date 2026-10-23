package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.headoffice.repository.StoreRepository;

/**
 * Item variants (ZEN), step 9: the stores' ERP customer numbers a connector reads the invoices of: every number a store
 * has (the query takes them active or not), trimmed, the blank ones left out, each once, sorted.
 */
class HoErpInvoiceCustomersTest {

	private static StoreRepository stores(List<String> numbers) {
		return (StoreRepository) Proxy.newProxyInstance(StoreRepository.class.getClassLoader(),
				new Class<?>[] { StoreRepository.class }, (proxy, method, args) -> {
					if ("findErpCustomerNumbers".equals(method.getName())) {
						return numbers;
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}

	@Test
	@DisplayName("Trimmed, the blank ones left out, each once, sorted; no store with a number: none")
	void numbers() {
		HoErpInvoiceCustomers customers = new HoErpInvoiceCustomers(
				stores(Arrays.asList(" 4110002 ", "4110001", "", "  ", null, "4110001")));
		assertEquals(Arrays.asList("4110001", "4110002"), new ArrayList<>(customers.storeCustomerNumbers()));
		assertTrue(new HoErpInvoiceCustomers(stores(new ArrayList<>())).storeCustomerNumbers().isEmpty());
	}
}
