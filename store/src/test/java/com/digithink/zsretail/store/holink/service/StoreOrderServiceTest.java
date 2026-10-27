package com.digithink.zsretail.store.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.core.dto.NavItemDTO;
import com.digithink.zsretail.core.dto.NavItemFilter;
import com.digithink.zsretail.core.link.dto.StoreOrderStatusCopyDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemImage;
import com.digithink.zsretail.core.model.enumeration.ImageSource;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemImageRepository;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.service.NavItemService;
import com.digithink.zsretail.core.service.QuantityPolicy;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.core.support.InMemoryLoyalty;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.store.holink.client.HeadOfficeClient;
import com.digithink.zsretail.store.holink.dto.DownApplyResult;
import com.digithink.zsretail.store.holink.dto.StoreOrderCatalogueLineDTO;
import com.digithink.zsretail.store.holink.dto.StoreOrderVariantsDTO;
import com.digithink.zsretail.store.holink.dto.StoreOrderViewDTO;
import com.digithink.zsretail.store.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.store.holink.enumeration.StoreOrderStatus;
import com.digithink.zsretail.store.holink.model.DownRecord;
import com.digithink.zsretail.store.holink.model.StoreOrder;
import com.digithink.zsretail.store.support.InMemoryStoreLink;
import com.digithink.zsretail.store.support.InMemoryStoreOrders;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.1.1, the orders of a store to its head office: the real StoreOrderService and HeadOfficeClient over in-memory tables
 * and a mock head office. One draft at a time, its number per store and year; one line per item, the quantity replaced,
 * 0 removing it; only active ERP items; send and cancel only from the draft; without the head office's answer (or with
 * its refusal) nothing is saved; the variants' stock is live or not at all (503). The statuses coming down: through
 * SupplyDownHandler, tracked in hol_down_record.
 */
class StoreOrderServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 27, 10, 0);
	private static final String URL = "http://ho:888/zsretail/api";
	private static final String ORDERS = URL + "/ho/orders";
	private static final String STOCK = ORDERS + "/variant-stock?navItemNo=70061";
	private static final String FIRST = "S01-2026-000001";

	private InMemoryItems db;
	private InMemoryStoreOrders orders;
	private NavItemService navItems;
	private ItemBarcodeRepository barcodes;
	private ItemImageRepository images;
	private MockRestServiceServer server;
	private StoreOrderService service;
	private Item small;
	private Item large;

	@BeforeEach
	void setUp() {
		db = new InMemoryItems(100_000);
		orders = new InMemoryStoreOrders(900_000);
		small = variant("70061~S", "S", "Taille S", null);
		large = variant("70061~L", "L", "Taille L", "l.png");
		navItems = mock(NavItemService.class);
		Map<String, List<Item>> variants = new LinkedHashMap<>();
		variants.put("70061", Arrays.asList(small, large));
		when(navItems.activeVariantsOf(Collections.singletonList("70061"))).thenReturn(variants);
		barcodes = mock(ItemBarcodeRepository.class);
		images = mock(ItemImageRepository.class);
		GeneralSetupService generalSetup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "DEFAULT_LOCATION".equals(code) ? "S01" : null;
			}
		};
		RestTemplate restTemplate = new RestTemplate();
		server = MockRestServiceServer.bindTo(restTemplate).build();
		HeadOfficeClient client = new HeadOfficeClient(restTemplate, generalSetup, URL, "KEY", "3.1.1");
		QuantityPolicy wholeOnly = new QuantityPolicy() {
			@Override
			public void check(BigDecimal quantity, String itemLabel) {
				if (!Quantities.isWhole(quantity)) {
					throw new IllegalArgumentException("Quantity of item " + itemLabel + ": decimal quantities are not"
							+ " allowed in this store");
				}
			}
		};
		service = new StoreOrderService(orders.repository(), db.itemRepository(), barcodes, images, navItems, client,
				wholeOnly, TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction(),
				() -> NOW);
	}

	private Item variant(String code, String variantCode, String description, String image) {
		Item item = db.item(code, 21.0, null);
		item.setName("CHEMISE");
		item.setNavItemNo("70061");
		item.setVariantCode(variantCode);
		item.setVariantDescription(description);
		item.setSizeCode(variantCode);
		item.setPublicPrice(25.0);
		item.setImageUrl(image);
		return item;
	}

	private StoreOrderViewDTO put(Item item, int quantity) {
		return service.putLine(item.getId(), Quantities.of(quantity), "admin").orElse(null);
	}

	private StoreOrder draft() {
		List<StoreOrder> drafts = orders.drafts();
		assertTrue(drafts.size() <= 1, "one draft at most: " + drafts.size());
		return drafts.isEmpty() ? null : drafts.get(0);
	}

	private static ResponseCreator unreachable() {
		return request -> {
			throw new ConnectException("Connection refused");
		};
	}

	private void expectSend(String number, ResponseCreator answer) {
		server.expect(requestTo(ORDERS)).andExpect(method(HttpMethod.POST)).andExpect(header("X-Store-Code", "S01"))
				.andExpect(header("X-Store-Key", "KEY")).andExpect(jsonPath("$.number").value(number))
				.andRespond(answer);
	}

	private static ResponseCreator accepted(String number) {
		return withSuccess("{\"documentNumber\":\"" + number + "\",\"accepted\":true}", MediaType.APPLICATION_JSON);
	}

	// ─── The draft ───────────────────────────────────────────────

	@Test
	@DisplayName("The first line makes the draft, numbered store code, year, sequence; the item's codes copied on the line")
	void firstLineMakesTheDraft() {
		assertFalse(service.draft().isPresent());
		StoreOrderViewDTO view = put(small, 2);
		assertEquals(FIRST, view.getNumber());
		assertEquals("DRAFT", view.getStatus());
		assertEquals(1, view.getLineCount());
		assertEquals(0, new BigDecimal("2").compareTo(view.getTotalQty()));
		StoreOrderViewDTO.Line line = view.getLines().get(0);
		assertEquals(small.getId(), line.getItemId());
		assertEquals("70061~S", line.getItemCode());
		assertEquals("70061", line.getNavItemNo());
		assertEquals("S", line.getVariantCode());
		assertEquals("CHEMISE", line.getDescription());
		assertEquals("Taille S", line.getVariantDescription());
		assertEquals("admin", draft().getCreatedBy());
		assertEquals(FIRST, service.draft().get().getNumber());
	}

	@Test
	@DisplayName("The same variant again replaces its quantity; another variant is another line; 0 removes a line")
	void replaceAndRemove() {
		put(small, 2);
		put(small, 5);
		StoreOrderViewDTO view = put(large, 1);
		assertEquals(2, view.getLineCount());
		assertEquals(0, new BigDecimal("6").compareTo(view.getTotalQty()), "5 + 1, the 2 replaced");
		view = put(small, 0);
		assertEquals(1, view.getLineCount());
		assertEquals(large.getId(), view.getLines().get(0).getItemId());
		view = service.removeLine(large.getId(), "admin").get();
		assertEquals(0, view.getLineCount());
		assertEquals(1, orders.orders.size(), "still one draft, empty");
	}

	@Test
	@DisplayName("A 0 without a draft makes none; removing a line or setting the note without a draft: empty")
	void noDraftForZero() {
		assertFalse(service.putLine(small.getId(), BigDecimal.ZERO, "admin").isPresent());
		assertFalse(service.removeLine(small.getId(), "admin").isPresent());
		assertFalse(service.setNote("vite", "admin").isPresent());
		assertTrue(orders.orders.isEmpty());
	}

	@Test
	@DisplayName("One draft at a time: lines go to it; after it is sent the next line makes the next number")
	void singleDraft() {
		put(small, 1);
		put(large, 1);
		assertEquals(1, orders.orders.size());
		StoreOrder first = draft();
		expectSend(FIRST, accepted(FIRST));
		service.send(first.getId(), "admin");
		assertNull(draft());
		StoreOrderViewDTO next = put(small, 3);
		assertEquals("S01-2026-000002", next.getNumber());
		assertEquals(2, orders.orders.size());
		assertEquals(1, orders.drafts().size());
	}

	@Test
	@DisplayName("The sequence goes on from the year's highest number of the store; a new year starts at 1")
	void numbers() {
		orders.add("S01-2026-000041", StoreOrderStatus.POSTED);
		orders.add("S01-2025-000900", StoreOrderStatus.POSTED);
		orders.add("S011-2026-000077", StoreOrderStatus.POSTED); // another store code that starts the same
		assertEquals("S01-2026-000042", service.nextNumber("S01", 2026));
		assertEquals("S01-2027-000001", service.nextNumber("S01", 2027));
		assertEquals("A_B-2026-000001", service.nextNumber("A_B", 2026));
		assertEquals("A[_]B-2026-%", StoreOrderService.likePrefix("A_B-2026-"));
	}

	@Test
	@DisplayName("Only an active ERP item is ordered, in whole pieces where the store allows no decimals")
	void onlyActiveErpItems() {
		Item emtop = db.item("EMTOP-1", 10.0, null);
		assertThrows(IllegalArgumentException.class, () -> put(emtop, 1), "no ERP item number");
		large.setActive(false);
		assertThrows(IllegalArgumentException.class, () -> put(large, 1), "inactive");
		assertThrows(IllegalArgumentException.class,
				() -> service.putLine(small.getId(), new BigDecimal("1.5"), "admin"), "decimal");
		assertThrows(IllegalArgumentException.class,
				() -> service.putLine(small.getId(), new BigDecimal("-1"), "admin"), "negative");
		assertThrows(IllegalArgumentException.class, () -> service.putLine(999L, Quantities.of(1), "admin"), "unknown");
		assertTrue(orders.orders.isEmpty(), "no draft made by a refused line");
	}

	@Test
	@DisplayName("The note of the draft, at most 500 characters")
	void note() {
		put(small, 1);
		assertEquals("Livrer lundi", service.setNote("  Livrer lundi ", "admin").get().getNote());
		assertNull(service.setNote(" ", "admin").get().getNote());
		char[] tooLong = new char[501];
		Arrays.fill(tooLong, 'x');
		assertThrows(IllegalArgumentException.class, () -> service.setNote(new String(tooLong), "admin"));
	}

	// ─── Send ────────────────────────────────────────────────────

	@Test
	@DisplayName("Send: the order up with the store code as its location and its lines; accepted, SENT at the store clock")
	void sendAccepted() {
		put(small, 2);
		put(large, 1);
		service.setNote("Livrer lundi", "admin");
		server.expect(requestTo(ORDERS)).andExpect(method(HttpMethod.POST)).andExpect(header("X-Store-Code", "S01"))
				.andExpect(jsonPath("$.number").value(FIRST)).andExpect(jsonPath("$.locationCode").value("S01"))
				.andExpect(jsonPath("$.note").value("Livrer lundi"))
				.andExpect(jsonPath("$.sentAt").value("2026-10-27T10:00:00"))
				.andExpect(jsonPath("$.lines.length()").value(2))
				.andExpect(jsonPath("$.lines[0].itemCode").value("70061~S"))
				.andExpect(jsonPath("$.lines[0].navItemNo").value("70061"))
				.andExpect(jsonPath("$.lines[0].variantCode").value("S"))
				.andExpect(jsonPath("$.lines[0].quantity").value(2)).andRespond(accepted(FIRST));
		StoreOrderViewDTO sent = service.send(draft().getId(), "admin").get();
		server.verify();
		assertEquals("SENT", sent.getStatus());
		assertEquals(NOW, sent.getSentAt());
		assertEquals(StoreOrderStatus.SENT, orders.byNumber(FIRST).getStatus());
	}

	@Test
	@DisplayName("Send without the head office (unreachable, HTTP 500, 404): 503, the order stays a draft, nothing saved")
	void sendWithoutHeadOffice() {
		put(small, 2);
		Long id = draft().getId();
		int saves = orders.saves;
		for (ResponseCreator answer : Arrays.asList(unreachable(), withStatus(HttpStatus.INTERNAL_SERVER_ERROR),
				withStatus(HttpStatus.NOT_FOUND))) {
			server.reset();
			expectSend(FIRST, answer);
			StoreOrderService.HeadOfficeUnavailableException e = assertThrows(
					StoreOrderService.HeadOfficeUnavailableException.class, () -> service.send(id, "admin"));
			assertTrue(e.getMessage().startsWith("The head office is needed to send order " + FIRST), e.getMessage());
			StoreOrder order = orders.orders.get(id);
			assertEquals(StoreOrderStatus.DRAFT, order.getStatus());
			assertNull(order.getSentAt());
		}
		assertEquals(saves, orders.saves, "nothing saved");
	}

	@Test
	@DisplayName("Send refused by the head office: 409 with its reason, the order stays a draft")
	void sendRefused() {
		put(small, 2);
		Long id = draft().getId();
		expectSend(FIRST, withSuccess("{\"documentNumber\":\"" + FIRST + "\",\"accepted\":false,\"message\":"
				+ "\"item 70061~S blocked in NAV\"}", MediaType.APPLICATION_JSON));
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.send(id, "admin"));
		assertEquals("The head office refused order " + FIRST + ": item 70061~S blocked in NAV", e.getMessage());
		assertEquals(StoreOrderStatus.DRAFT, orders.orders.get(id).getStatus());
	}

	@Test
	@DisplayName("Send and cancel only from the draft; an empty draft is not sent; an unknown order is empty")
	void onlyFromDraft() {
		put(small, 1);
		Long id = draft().getId();
		expectSend(FIRST, accepted(FIRST));
		service.send(id, "admin");
		assertThrows(IllegalStateException.class, () -> service.send(id, "admin"), "already sent");
		assertThrows(IllegalStateException.class, () -> service.cancel(id, "admin"), "a sent order is not cancelled");
		assertEquals(StoreOrderStatus.SENT, orders.orders.get(id).getStatus());

		put(small, 1);
		Long second = draft().getId();
		put(small, 0);
		IllegalStateException empty = assertThrows(IllegalStateException.class, () -> service.send(second, "admin"));
		assertTrue(empty.getMessage().contains("has no line"), empty.getMessage());
		assertFalse(service.send(123L, "admin").isPresent());
		assertFalse(service.cancel(123L, "admin").isPresent());
	}

	@Test
	@DisplayName("Cancel: the draft CANCELLED, never sent; the next line makes a new draft")
	void cancel() {
		put(small, 1);
		Long id = draft().getId();
		assertEquals("CANCELLED", service.cancel(id, "admin").get().getStatus());
		assertThrows(IllegalStateException.class, () -> service.cancel(id, "admin"));
		assertThrows(IllegalStateException.class, () -> service.send(id, "admin"));
		assertEquals("S01-2026-000002", put(large, 1).getNumber());
		server.verify(); // no call to the head office
	}

	// ─── Variants and catalogue ──────────────────────────────────

	@Test
	@DisplayName("Variants: NAV's stock merged by variant (none: null, another NAV item ignored), images, the draft's quantity")
	void variantsWithStock() {
		put(large, 3);
		ItemImage image = new ItemImage();
		image.setId(5L);
		image.setItemId(small.getId());
		image.setSource(ImageSource.URL);
		image.setPath("https://cdn/s.png");
		image.setMain(true);
		when(images.findOfItems(anyCollection())).thenReturn(Collections.singletonList(image));
		server.expect(requestTo(STOCK)).andExpect(method(HttpMethod.GET)).andExpect(header("X-Store-Code", "S01"))
				.andRespond(withSuccess("[{\"itemNo\":\"70061\",\"variantCode\":\"s\",\"quantity\":4},"
						+ "{\"itemNo\":\"OTHER\",\"variantCode\":\"L\",\"quantity\":99}]", MediaType.APPLICATION_JSON));
		StoreOrderVariantsDTO answer = service.variants(" 70061 ").get();
		server.verify();
		assertEquals("CHEMISE", answer.getName());
		StoreOrderVariantsDTO.Variant s = answer.getVariants().get(0);
		StoreOrderVariantsDTO.Variant l = answer.getVariants().get(1);
		assertEquals(0, new BigDecimal("4").compareTo(s.getStock()));
		assertNull(l.getStock(), "no NAV row for L (the 99 is another item's): unknown, never 0");
		assertEquals(25.0, s.getPrice());
		assertEquals(1, s.getImages().size());
		assertEquals("https://cdn/s.png", s.getImages().get(0).getPath());
		assertTrue(l.getImages().isEmpty());
		assertEquals("l.png", l.getMainImage());
		assertNull(s.getDraftQuantity());
		assertEquals(0, new BigDecimal("3").compareTo(l.getDraftQuantity()));
	}

	@Test
	@DisplayName("Variants without the head office: 503, no partial answer; an unknown NAV item asks nothing (empty)")
	void variantsWithoutHeadOffice() {
		server.expect(requestTo(STOCK)).andRespond(unreachable());
		StoreOrderService.HeadOfficeUnavailableException e = assertThrows(
				StoreOrderService.HeadOfficeUnavailableException.class, () -> service.variants("70061"));
		assertTrue(e.getMessage().contains("OFFLINE"), e.getMessage());
		server.reset();
		server.expect(requestTo(STOCK)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		assertThrows(StoreOrderService.HeadOfficeUnavailableException.class, () -> service.variants("70061"));
		when(navItems.activeVariantsOf(Collections.singletonList("NOPE"))).thenReturn(new LinkedHashMap<>());
		assertFalse(service.variants("NOPE").isPresent());
		assertThrows(IllegalArgumentException.class, () -> service.variants(" "));
	}

	@Test
	@DisplayName("Catalogue: the grouped read with the barcode matches and active only; price and image of the variants")
	void catalogue() {
		NavItemDTO line = new NavItemDTO();
		line.setNavItemNo("70061");
		line.setName("CHEMISE");
		line.setVariants(2);
		line.setCategoryCode("VET");
		Page<NavItemDTO> page = new PageImpl<>(Collections.singletonList(line), PageRequest.of(0, 20), 1);
		when(navItems.page(any(NavItemFilter.class), anyInt(), anyInt())).thenReturn(page);
		when(barcodes.findItemIdsByBarcodeLike(eq("%6191%"), any())).thenReturn(Collections.singletonList(large.getId()));
		NavItemFilter filter = new NavItemFilter();
		filter.setSearch("6191");
		filter.setActiveOnly(false);
		filter.setSectionCode("MANCHE");
		StoreOrderCatalogueLineDTO dto = service.catalogue(filter, 0, 20).getContent().get(0);
		ArgumentCaptor<NavItemFilter> asked = ArgumentCaptor.forClass(NavItemFilter.class);
		verify(navItems).page(asked.capture(), eq(0), eq(20));
		assertTrue(asked.getValue().isActiveOnly(), "active variants only, whatever was asked");
		assertEquals(Collections.singletonList(large.getId()), asked.getValue().getBarcodeItemIds());
		assertEquals("MANCHE", asked.getValue().getSectionCode());
		assertEquals("70061", dto.getNavItemNo());
		assertEquals(2, dto.getVariantCount());
		assertEquals(25.0, dto.getPrice(), "the first variant's public price");
		assertEquals(19, dto.getVatRate());
		assertEquals("l.png", dto.getMainImage(), "the first variant with an image");
		assertEquals(large.getId(), dto.getMainImageItemId(), "its item, for item-image/item/{id}");
		assertEquals("VET", dto.getCategoryCode());
	}

	@Test
	@DisplayName("The price is the public price, else the item's own price")
	void price() {
		small.setPublicPrice(null);
		assertEquals(21.0, StoreOrderService.priceOf(small));
	}

	// ─── Statuses from the head office ───────────────────────────

	private DownApplyResult statusDown(InMemoryStoreLink link, String number, String status, String navNo,
			String error) {
		StoreOrderStatusCopyDTO copy = new StoreOrderStatusCopyDTO();
		copy.setOrderNumber(number);
		copy.setStatus(status);
		copy.setNavOrderNo(navNo);
		copy.setErrorMessage(error);
		SupplyDownHandler handler = new SupplyDownHandler(null, link.downRecordLog(),
				TransactionOperations.withoutTransaction(), null, new StoreOrderStatusWriter(orders.repository()));
		return handler.apply(Collections.singletonList(new ObjectMapper().valueToTree(copy)), Collections.emptyList());
	}

	@Test
	@DisplayName("Down: POSTED with its NAV number, then ERROR with its reason; the same again changes nothing; tracked")
	void statusesDown() {
		InMemoryStoreLink link = new InMemoryStoreLink(new InMemoryLoyalty(500_000));
		StoreOrder order = orders.add(FIRST, StoreOrderStatus.SENT);
		assertEquals(1, statusDown(link, FIRST, "POSTED", "CMD-0042", null).getApplied());
		assertEquals(StoreOrderStatus.POSTED, order.getStatus());
		assertEquals("CMD-0042", order.getNavOrderNo());
		assertEquals(1, statusDown(link, FIRST, "POSTED", "CMD-0042", null).getUnchanged());
		DownRecord tracked = link.downRecords.get("ORD:" + FIRST);
		assertEquals(DownRecordStatus.APPLIED, tracked.getStatus());
		assertEquals(FIRST, tracked.getRecordName());

		assertEquals(1, statusDown(link, FIRST, "ERROR", null, "location S01 blocked").getApplied());
		assertEquals(StoreOrderStatus.ERROR, order.getStatus());
		assertEquals("location S01 blocked", order.getErrorMessage());
		assertNull(order.getNavOrderNo());
	}

	@Test
	@DisplayName("Down: an order never sent or unknown here is left as it is (noted); a bad status is an error, retried")
	void statusesDownIgnored() {
		InMemoryStoreLink link = new InMemoryStoreLink(new InMemoryLoyalty(500_000));
		StoreOrder draft = orders.add(FIRST, StoreOrderStatus.DRAFT);
		assertEquals(1, statusDown(link, FIRST, "POSTED", "CMD-1", null).getUnchanged());
		assertEquals(StoreOrderStatus.DRAFT, draft.getStatus());
		assertEquals("the order was never sent from this store", link.downRecords.get("ORD:" + FIRST).getInfo());
		assertEquals(1, statusDown(link, "S01-2026-000777", "POSTED", "CMD-2", null).getUnchanged());
		assertEquals("no such order at this store", link.downRecords.get("ORD:S01-2026-000777").getInfo());
		DownApplyResult bad = statusDown(link, FIRST, "DRAFT", null, null);
		assertEquals(1, bad.getErrors());
		assertEquals(DownRecordStatus.ERROR, link.downRecords.get("ORD:" + FIRST).getStatus());
	}
}
