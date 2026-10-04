package com.digithink.zsretail.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.dto.StandaloneQuickProductRequestDTO;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.support.TestModes;

/**
 * Head office plan, tasks 9.1c and 9.1d: each gate that read isStandalone() now asks a step 9 question and answers as
 * before. On an installation with an ERP (an ERP store, a head office with an ERP) every gate refuses with 403 and its
 * old message, before any service is used (the controllers here have no service). Which question each gate asks:
 * docs/deployment-modes.md, "Step 9 questions".
 */
class ModeGateTest {

	/** An ERP store and a head office with an ERP (headoffice-dynamics-dev flags). */
	private static ApplicationModeService[] erpInstallations() {
		return new ApplicationModeService[] { TestModes.erp(), TestModes.of(new MockEnvironment()
				.withProperty("application.standalone", "false").withProperty("node.type", "HEAD_OFFICE")) };
	}

	private static void set(Object target, Class<?> declaring, String name, Object value) {
		try {
			Field field = declaring.getDeclaredField(name);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void refused(String gate, ResponseEntity<?> answer, String message) {
		assertEquals(HttpStatus.FORBIDDEN, answer.getStatusCode(), gate);
		assertTrue(String.valueOf(answer.getBody()).contains(message), gate + ": " + answer.getBody());
	}

	private static void eachErp(Function<ApplicationModeService, Runnable> check) {
		for (ApplicationModeService mode : erpInstallations()) {
			assertTrue(mode.isCatalogueFromErp() && mode.isCustomersFromErp() && mode.isSupplyFromErp() && mode.hasErp());
			check.apply(mode).run();
		}
	}

	private static ItemAPI itemApi(ApplicationModeService mode) {
		ItemAPI api = new ItemAPI();
		set(api, ItemAPI.class, "applicationModeService", mode);
		return api;
	}

	@Test
	@DisplayName("9.1c catalogue: item quick product, create, update, delete, family and sub-family create, data import: 403 with an ERP")
	void catalogueGates() {
		eachErp(mode -> () -> {
			ItemAPI items = itemApi(mode);
			refused("quick product", items.createStandaloneQuickProduct(new StandaloneQuickProductRequestDTO()),
					"Product creation is only available in standalone mode.");
			refused("item create", items.create(new Item()), "Item creation is only available in standalone mode.");
			refused("item update", items.update(1L, new Item()), "Item update is only available in standalone mode.");
			refused("item delete", items.deleteById(1L), "Item deletion is only available in standalone mode.");
			refused("family create", new ItemFamilyAPI(mode, null).create(new ItemFamily()), "Item family creation is only available in standalone mode.");
			ItemSubFamilyAPI subFamilies = new ItemSubFamilyAPI();
			set(subFamilies, ItemSubFamilyAPI.class, "applicationModeService", mode);
			refused("sub-family create", subFamilies.create(new ItemSubFamily()), "standalone mode");
			DataImportAPI imports = new DataImportAPI(mode, null, null, null);
			MockMultipartFile file = new MockMultipartFile("file", "items.xlsx", null, new byte[] { 1 });
			refused("import preview", imports.preview(file), "Data import is only available in standalone mode.");
			refused("import execute", imports.execute(file, "ITEMS", "{}"),
					"Data import is only available in standalone mode.");
		});
	}

	@Test
	@DisplayName("9.1c customers: customer create and the three invoices from POS tickets: 403 with an ERP")
	void customerGates() {
		eachErp(mode -> () -> {
			CustomerAPI customers = new CustomerAPI();
			set(customers, CustomerAPI.class, "applicationModeService", mode);
			refused("customer create", customers.create(new Customer()), "Customer creation is only available in standalone mode.");
			InvoiceAPI invoices = new InvoiceAPI();
			set(invoices, InvoiceAPI.class, "applicationModeService", mode);
			String message = "Invoice creation from POS is only available in standalone mode.";
			refused("eligible tickets", invoices.getEligibleTickets(1L, null, null), message);
			InvoiceAPI.CreateInvoiceRequest request = new InvoiceAPI.CreateInvoiceRequest();
			request.setTicketIds(Collections.singletonList(1L));
			refused("create invoice", invoices.createInvoice(request), message);
			refused("invoice from ticket", invoices.createInvoiceFromTicket(1L, null), message);
		});
	}
}
