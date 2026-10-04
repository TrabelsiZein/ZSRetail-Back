package com.digithink.zsretail.holink.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.controller.VendorAPI;
import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryInputDTO;
import com.digithink.zsretail.headoffice.enumeration.InvoiceRhythm;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.CopiesDownFeed;
import com.digithink.zsretail.headoffice.service.HoDeliveryService;
import com.digithink.zsretail.headoffice.service.HoSupplyInvoiceService;
import com.digithink.zsretail.headoffice.service.HoSupplyPriceService;
import com.digithink.zsretail.headoffice.service.InMemoryDeliveries;
import com.digithink.zsretail.headoffice.service.InMemoryDownTables;
import com.digithink.zsretail.headoffice.service.InMemoryInvoices;
import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.model.CompanyInformation;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.model.PurchaseInvoiceLine;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.CompanyInformationRepository;
import com.digithink.zsretail.repository.PurchaseInvoiceHeaderRepository;
import com.digithink.zsretail.repository.PurchaseInvoiceLineRepository;
import com.digithink.zsretail.repository.VendorRepository;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryLoyalty;
import com.digithink.zsretail.support.InMemoryReceivedDeliveries;
import com.digithink.zsretail.support.InMemoryStock;
import com.digithink.zsretail.support.InMemoryStoreLink;

/**
 * Head office plan, step 7B, part 3, end to end over the copies down: the real head office (HoDeliveryService,
 * HoSupplyInvoiceService, CopiesDownFeed) and the real store B (SupplyDownHandler, DeliveryReceptionService,
 * SupplyInvoiceWriter), each over its own in-memory tables. A per-BL invoice arrives once as a purchase invoice of the
 * vendor HEAD_OFFICE (created once), origin HEAD_OFFICE, totals copied; the supply price becomes the cost of the head
 * office items only; the BL gets the invoice number; the tax stamp line names the store's TAX_STAMP item; an item not
 * here gives a line without item; the head office vendor is consult-only.
 */
class SupplyInvoiceRoundTripTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 15, 0);

	// Head office
	private final InMemoryCatalogue ho = new InMemoryCatalogue(1);
	private final InMemoryStock hoStock = new InMemoryStock(ho);
	private final InMemoryDeliveries hoTables = new InMemoryDeliveries(ho);
	private final InMemoryInvoices hoInvoices = new InMemoryInvoices();
	private final Map<String, String> hoSettings = new HashMap<>();
	private CopiesDownFeed feed;
	private HoDeliveryService hoDeliveries;
	private Store b;

	// Store B
	private final InMemoryCatalogue db = new InMemoryCatalogue(100_000);
	private final InMemoryStock stock = new InMemoryStock(db);
	private final InMemoryStoreLink link = new InMemoryStoreLink(new InMemoryLoyalty(500_000));
	private final InMemoryReceivedDeliveries received = new InMemoryReceivedDeliveries(900_000);
	private final Map<Long, PurchaseInvoiceHeader> headers = new LinkedHashMap<>();
	private final List<PurchaseInvoiceLine> lines = new ArrayList<>();
	private final Map<Long, Vendor> vendors = new LinkedHashMap<>();
	private long nextId = 950_000;
	private DeliveryReceptionService reception;
	private SupplyDownHandler handler;
	private String cursor = "";

	@BeforeEach
	void setUp() {
		CopiesDownFeed[] holder = new CopiesDownFeed[1];
		HoSupplyPriceService prices = new HoSupplyPriceService(ho.supplyPriceRepository(), ho.priceLineRepository(),
				hoStock.itemRepository());
		CompanyInformation company = new CompanyInformation();
		company.setCompanyName("Happy Head Office SA");
		company.setMatriculeFiscal("0000001/A/M/000");
		company.setAddress("1 rue du Lac");
		CompanyInformationRepository companies = proxy(CompanyInformationRepository.class,
				(method, args) -> "findAll".equals(method) ? new ArrayList<>(Collections.singletonList(company)) : UNHANDLED);
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return hoSettings.get(code);
			}
		};
		HoSupplyInvoiceService[] invoices = new HoSupplyInvoiceService[1];
		hoDeliveries = new HoDeliveryService(hoTables.deliveryRepository(), ho.storeRepository(),
				hoStock.itemRepository(), hoTables.sequenceRepository(), hoStock.stockService(),
				hoStock.stockMovementService(), () -> holder[0], TransactionOperations.withoutTransaction(), () -> NOW,
				() -> invoices[0]);
		invoices[0] = new HoSupplyInvoiceService(hoInvoices.repository(), hoTables.deliveryRepository(),
				ho.storeRepository(), hoStock.itemRepository(), hoTables.sequenceRepository(), prices, companies, setup,
				() -> holder[0], TransactionOperations.withoutTransaction(), () -> NOW);
		feed = new InMemoryDownTables().feed(Collections.singletonList(hoDeliveries));
		holder[0] = feed;
		for (String code : new String[] { "B001", "B002", "B009", "TAX_STAMP" }) {
			Item item = ho.item(code, 10.0, null);
			item.setStockQuantity(500);
		}
		ho.supplyPrice(ho.itemByCode("B001").get(), 6.0);
		ho.supplyPrice(ho.itemByCode("B002").get(), 2.5);
		ho.supplyPrice(ho.itemByCode("B009").get(), 1.0);
		b = ho.store("B");
		b.setDeliveriesInvoiced(true);
		b.setInvoiceRhythm(InvoiceRhythm.PER_BL);
		hoDeliveries.initialise();
		feed.initialise();

		// Store B: the head office items as the catalogue left them, its own tax stamp item
		for (String code : new String[] { "B001", "B002" }) {
			Item item = db.item(code, 10.0, null);
			item.setOrigin(RecordOrigin.HEAD_OFFICE);
			item.setCostPrice(9.0);
		}
		db.item("TAX_STAMP", 0.1, null);
		reception = new DeliveryReceptionService(received.repository(), stock.itemRepository(), stock.stockService(),
				stock.stockMovementService(), TransactionOperations.withoutTransaction(), () -> NOW);
		SupplyInvoiceWriter writer = new SupplyInvoiceWriter(headerRepository(), lineRepository(), vendorRepository(),
				stock.itemRepository(), received.repository());
		handler = new SupplyDownHandler(reception, link.downRecordLog(), TransactionOperations.withoutTransaction(),
				writer);
	}

	private void pull() {
		CopiesDownAnswerDTO page = feed.pull(b, "SUPPLY", cursor, 500);
		handler.apply(page.getRecords(), page.getRemoved());
		cursor = page.getCursor();
	}

	/** A BL of these items sent to B, pulled, received as sent, and its confirmation applied at the head office. */
	private ReceivedDelivery deliverAndConfirm(String... codes) {
		DeliveryInputDTO input = new DeliveryInputDTO();
		input.setStoreId(b.getId());
		input.setLines(Arrays.stream(codes).map(code -> new DeliveryInputDTO.Line(code, 10)).collect(Collectors.toList()));
		DeliveryDTO sent = hoDeliveries.validate(hoDeliveries.create(input).getId(), "admin").get();
		pull();
		ReceivedDelivery bl = received.byNumber(sent.getNumber());
		reception.receive(bl.getId(), null, "responsible");
		assertTrue(hoDeliveries
				.receiveConfirmations(b, Collections.singletonList(SupplyPushService.confirmationOf(bl))).get(0)
				.isAccepted());
		return bl;
	}

	@Test
	@DisplayName("A per-BL invoice arrives once: purchase invoice of the vendor HEAD_OFFICE, origin HEAD_OFFICE, totals copied, cost of the item, the BL numbered")
	void invoiceArrivesOnce() {
		ReceivedDelivery bl = deliverAndConfirm("B001");
		pull();

		assertEquals(1, headers.size());
		PurchaseInvoiceHeader invoice = headers.values().iterator().next();
		assertEquals("FHO-2026-000001", invoice.getInvoiceNumber());
		assertEquals(LocalDate.of(2026, 10, 6), invoice.getInvoiceDate());
		assertEquals(RecordOrigin.HEAD_OFFICE, invoice.getOrigin());
		assertEquals(SupplyInvoiceWriter.VENDOR_CODE, invoice.getVendor().getVendorCode());
		assertEquals("Happy Head Office SA", invoice.getVendor().getName());
		assertEquals("Happy Head Office SA", invoice.getSnapshotVendorName());
		assertEquals("0000001/A/M/000", invoice.getSnapshotVendorTaxRegNo());
		assertEquals(60.0, invoice.getSubtotal());
		assertEquals(11.4, invoice.getTaxAmount());
		assertEquals(71.4, invoice.getTotalAmount());
		assertEquals("Head office invoice - BL " + bl.getNumber(), invoice.getNotes());
		assertEquals(1, lines.size());
		PurchaseInvoiceLine line = lines.get(0);
		assertEquals("B001", line.getItem().getItemCode());
		assertEquals(10, line.getQuantity());
		assertEquals(6.0, line.getUnitPrice());
		assertEquals(71.4, line.getLineTotalIncludingVat());
		assertEquals(7.14, line.getUnitPriceIncludingVat(), 0.0005);
		Item b001 = db.itemByCode("B001").get();
		assertEquals(6.0, b001.getLastDirectCost());
		assertEquals(6.0, b001.getLastDirectNetCost());
		assertEquals(6.0, b001.getCostPrice(), "costPrice too, for a head office item");
		assertEquals("FHO-2026-000001", bl.getInvoiceNumber());
		assertEquals("FHO-2026-000001", reception.get(bl.getId()).get().getInvoiceNumber());
		DownRecord tracked = link.downRecords.get("INV:FHO-2026-000001");
		assertEquals(DownRecordStatus.APPLIED, tracked.getStatus());
		assertNull(tracked.getInfo());

		cursor = "";
		pull(); // everything again from the start: nothing new
		assertEquals(1, headers.size());
		assertEquals(1, lines.size());
		assertEquals(1, vendors.size());
	}

	@Test
	@DisplayName("Second invoice: the same vendor; the tax stamp line names the store's TAX_STAMP item and changes no cost")
	void secondInvoiceAndStamp() {
		deliverAndConfirm("B001");
		hoSettings.put(HoSupplyInvoiceService.TAX_STAMP_SETTING, "true");
		deliverAndConfirm("B002");
		pull();

		assertEquals(2, headers.size());
		assertEquals(1, vendors.size(), "the vendor HEAD_OFFICE created once");
		PurchaseInvoiceLine stamp = lines.get(lines.size() - 1);
		assertEquals("TAX_STAMP", stamp.getItem().getItemCode());
		assertEquals(1.0, stamp.getUnitPrice());
		assertEquals(0, stamp.getVatPercent());
		assertNull(db.itemByCode("TAX_STAMP").get().getCostPrice(), "the stamp is not a cost");
		assertEquals(2.5, db.itemByCode("B002").get().getCostPrice());
	}

	@Test
	@DisplayName("An item not in B, or only as its own item: a line without item, its code in the description, no cost on the own item")
	void itemNotHere() {
		Item own = db.item("B009", 3.0, null); // the store's own item, same code
		own.setCostPrice(2.0);
		deliverAndConfirm("B001", "B009");
		pull();

		PurchaseInvoiceLine missing = lines.stream().filter(l -> l.getItem() == null).findFirst().get();
		assertTrue(missing.getLineDescription().contains("B009"), missing.getLineDescription());
		assertEquals(10, missing.getQuantity());
		assertEquals(2.0, own.getCostPrice(), "the store's own item keeps its cost");
		assertEquals("items not in this store: B009", link.downRecords.get("INV:FHO-2026-000001").getInfo());
	}

	@Test
	@DisplayName("The head office vendor is consult-only: create with its code, update and delete it answer 409; another vendor goes on")
	void headOfficeVendorGuard() throws Exception {
		deliverAndConfirm("B001");
		pull();
		Vendor headOffice = vendors.values().iterator().next();
		Vendor other = new Vendor();
		other.setId(nextId++);
		other.setVendorCode("V1");
		vendors.put(other.getId(), other);
		SupplyVendorGuard guard = new SupplyVendorGuard(vendorRepository());

		VendorAPI api = new VendorAPI();
		ApplicationModeService mode = new ApplicationModeService();
		set(mode, ApplicationModeService.class, "standalone", true);
		set(api, VendorAPI.class, "applicationModeService", mode);
		set(api, VendorAPI.class, "supplyVendorGuard",
				new StaticListableBeanFactory(Collections.singletonMap("guard", guard)).getBeanProvider(SupplyVendorGuard.class));
		Vendor withCode = new Vendor();
		withCode.setVendorCode("head_office");
		assertEquals(409, api.create(withCode).getStatusCodeValue());
		assertEquals(409, api.update(headOffice.getId(), new Vendor()).getStatusCodeValue());
		assertEquals(409, api.deleteById(headOffice.getId()).getStatusCodeValue());
		Vendor renamed = new Vendor();
		renamed.setVendorCode("HEAD_OFFICE");
		assertEquals(409, api.update(other.getId(), renamed).getStatusCodeValue(), "taking its code");
		assertNull(guard.update(other.getId(), new Vendor()));
		assertNull(guard.delete(other.getId()));
		assertNull(guard.create(new Vendor()));
	}

	private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	// ─── Store tables ────────────────────────────────────────────

	private PurchaseInvoiceHeaderRepository headerRepository() {
		return proxy(PurchaseInvoiceHeaderRepository.class, (method, args) -> {
			switch (method) {
				case "findByInvoiceNumber":
					return headers.values().stream().filter(h -> h.getInvoiceNumber().equals(args[0])).findFirst();
				case "save": {
					PurchaseInvoiceHeader header = (PurchaseInvoiceHeader) args[0];
					if (header.getId() == null) {
						header.setId(nextId++);
					}
					headers.put(header.getId(), header);
					return header;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	private PurchaseInvoiceLineRepository lineRepository() {
		return proxy(PurchaseInvoiceLineRepository.class, (method, args) -> {
			if ("save".equals(method)) {
				PurchaseInvoiceLine line = (PurchaseInvoiceLine) args[0];
				line.setId(nextId++);
				lines.add(line);
				return line;
			}
			return UNHANDLED;
		});
	}

	private VendorRepository vendorRepository() {
		return proxy(VendorRepository.class, (method, args) -> {
			switch (method) {
				case "findByVendorCode":
					return vendors.values().stream().filter(v -> args[0].equals(v.getVendorCode())).findFirst();
				case "findById":
					return Optional.ofNullable(vendors.get(args[0]));
				case "save": {
					Vendor vendor = (Vendor) args[0];
					if (vendor.getId() == null) {
						vendor.setId(nextId++);
					}
					vendors.put(vendor.getId(), vendor);
					return vendor;
				}
				default:
					return UNHANDLED;
			}
		});
	}
}
