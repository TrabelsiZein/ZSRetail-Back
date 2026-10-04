package com.digithink.zsretail.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.web.server.ResponseStatusException;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.dto.AdjustStockRequestDTO;
import com.digithink.zsretail.dto.ProcessPurchaseRequestDTO;
import com.digithink.zsretail.dto.SetPurchasePaidRequestDTO;
import com.digithink.zsretail.dto.QuickProductRequestDTO;
import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.Location;
import com.digithink.zsretail.model.Vendor;
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
			refused("quick product", items.createQuickProduct(new QuickProductRequestDTO()),
					"Product creation is not available with an ERP");
			refused("item create", items.create(new Item()), "Item creation is not available with an ERP");
			refused("item update", items.update(1L, new Item()), "Item update is not available with an ERP");
			refused("item delete", items.deleteById(1L), "Item deletion is not available with an ERP");
			refused("family create", new ItemFamilyAPI(mode, null).create(new ItemFamily()), "Item family creation is not available with an ERP");
			ItemSubFamilyAPI subFamilies = new ItemSubFamilyAPI();
			set(subFamilies, ItemSubFamilyAPI.class, "applicationModeService", mode);
			refused("sub-family create", subFamilies.create(new ItemSubFamily()), "not available with an ERP");
			DataImportAPI imports = new DataImportAPI(mode, null, null, null);
			MockMultipartFile file = new MockMultipartFile("file", "items.xlsx", null, new byte[] { 1 });
			refused("import preview", imports.preview(file), "Data import is not available with an ERP");
			refused("import execute", imports.execute(file, "ITEMS", "{}"),
					"Data import is not available with an ERP");
		});
	}

	@Test
	@DisplayName("9.1c customers: customer create and the three invoices from POS tickets: 403 with an ERP")
	void customerGates() {
		eachErp(mode -> () -> {
			CustomerAPI customers = new CustomerAPI();
			set(customers, CustomerAPI.class, "applicationModeService", mode);
			refused("customer create", customers.create(new Customer()), "Customer creation is not available with an ERP");
			InvoiceAPI invoices = new InvoiceAPI();
			set(invoices, InvoiceAPI.class, "applicationModeService", mode);
			String message = "Invoice creation from POS is not available with an ERP";
			refused("eligible tickets", invoices.getEligibleTickets(1L, null, null), message);
			InvoiceAPI.CreateInvoiceRequest request = new InvoiceAPI.CreateInvoiceRequest();
			request.setTicketIds(Collections.singletonList(1L));
			refused("create invoice", invoices.createInvoice(request), message);
			refused("invoice from ticket", invoices.createInvoiceFromTicket(1L, null), message);
		});
	}

	@Test
	@DisplayName("9.1d supply: purchases, purchase invoices, vendors, locations, stock adjustment: 403 with an ERP")
	void supplyGates() {
		eachErp(mode -> () -> {
			PurchaseHeaderAPI purchases = new PurchaseHeaderAPI();
			set(purchases, PurchaseHeaderAPI.class, "applicationModeService", mode);
			refused("vendor balance", purchases.getVendorBalance(null, null),
					"Vendor balance report is not available with an ERP");
			refused("purchase history", purchases.getHistory(0, 10, null, null, null, null, null),
					"Purchase history is not available with an ERP");
			refused("purchase details", purchases.getDetails(1L), "Purchase details are not available with an ERP");
			refused("process purchase", purchases.processPurchase(new ProcessPurchaseRequestDTO()),
					"Purchases are not available with an ERP");
			refused("purchase paid", purchases.setPaid(1L, new SetPurchasePaidRequestDTO()),
					"Purchase paid status is not available with an ERP");

			PurchaseInvoiceAPI purchaseInvoices = new PurchaseInvoiceAPI();
			set(purchaseInvoices, PurchaseInvoiceAPI.class, "applicationModeService", mode);
			String invoiceMessage = "Purchase invoices are not available with an ERP";
			forbidden("purchase invoice list", () -> purchaseInvoices.listPurchaseInvoices(null, null, null, null, 0, 20),
					invoiceMessage);
			forbidden("eligible purchases", () -> purchaseInvoices.getEligiblePurchases(1L, null, null), invoiceMessage);
			forbidden("purchase invoice create",
					() -> purchaseInvoices.createPurchaseInvoice(new PurchaseInvoiceAPI.CreatePurchaseInvoiceRequest()),
					invoiceMessage);
			forbidden("purchase invoice details", () -> purchaseInvoices.getPurchaseInvoiceDetails(1L), invoiceMessage);

			VendorAPI vendors = new VendorAPI();
			set(vendors, VendorAPI.class, "applicationModeService", mode);
			refused("vendor create", vendors.create(new Vendor()), "Vendor creation is not available with an ERP");
			refused("vendor update", vendors.update(1L, new Vendor()), "Vendor update is not available with an ERP");
			refused("vendor delete", vendors.deleteById(1L), "Vendor deletion is not available with an ERP");

			LocationAPI locations = new LocationAPI(mode);
			refused("location create", locations.create(new Location()),
					"Location creation is not available with an ERP");
			refused("location update", locations.update(1L, new Location()),
					"Location update is not available with an ERP");
			refused("location delete", locations.deleteById(1L), "Location deletion is not available with an ERP");

			refused("stock adjustment", itemApi(mode).adjustStock(1L, new AdjustStockRequestDTO()),
					"Stock adjustment is not available with an ERP");
		});
	}

	/** PurchaseInvoiceAPI refuses with a ResponseStatusException (403), as before. */
	private static void forbidden(String gate, Runnable call, String message) {
		ResponseStatusException e = assertThrows(ResponseStatusException.class, call::run, gate);
		assertEquals(HttpStatus.FORBIDDEN, e.getStatus(), gate);
		assertTrue(String.valueOf(e.getReason()).contains(message), gate + ": " + e.getReason());
	}
}
