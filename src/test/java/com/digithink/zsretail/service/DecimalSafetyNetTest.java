package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.persistence.EntityManager;
import javax.persistence.Query;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import com.digithink.zsretail.analytics.dto.TopProductDTO;
import com.digithink.zsretail.analytics.repository.AnalyticsRepository;
import com.digithink.zsretail.analytics.service.AnalyticsService;
import com.digithink.zsretail.config.WholeQuantityExceptionResolver;
import com.digithink.zsretail.controller.ReturnHeaderAPI;
import com.digithink.zsretail.dto.CartCalculateRequestDTO;
import com.digithink.zsretail.dto.CartCalculateResponseDTO;
import com.digithink.zsretail.dto.CloseSessionRequestDTO;
import com.digithink.zsretail.dto.ImportFieldMappingDTO;
import com.digithink.zsretail.dto.ImportResultDTO;
import com.digithink.zsretail.dto.LoyaltyAdjustmentRequestDTO;
import com.digithink.zsretail.dto.ProcessSaleRequestDTO;
import com.digithink.zsretail.dto.report.SalesReportRowDTO;
import com.digithink.zsretail.dto.report.StockMovementReportRowDTO;
import com.digithink.zsretail.dto.report.StockReportRowDTO;
import com.digithink.zsretail.headoffice.dto.PromotionCopyDTO;
import com.digithink.zsretail.headoffice.dto.ReturnLineCopyDTO;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemComposition;
import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.model.enumeration.PromotionScope;
import com.digithink.zsretail.model.enumeration.PromotionType;
import com.digithink.zsretail.repository.CustomerRepository;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository.ItemCompositionRepository;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.repository.LocationRepository;
import com.digithink.zsretail.repository.PromotionRepository;
import com.digithink.zsretail.repository.SalesDiscountRepository;
import com.digithink.zsretail.repository.SalesPriceRepository;
import com.digithink.zsretail.repository.VendorRepository;
import com.digithink.zsretail.utils.WholeQuantityDeserializer.NotWholeQuantity;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 2.2.1, step 5, the safety net: with decimal sales in the database the readers keep the decimals (reports, dashboard,
 * return screen), the writers not converted refuse a decimal by name (invoices, compositions), the Excel item import
 * refuses a stock cell that is not whole by row, a decimal line is not eligible for quantity-based promotions, and
 * the remaining whole-number quantity fields refuse 1.5 with a 400 naming the field (scoped guard, Jackson's global
 * setting unchanged). Each with the whole-number regression: 2 and 2.0 read as in 2.2.0.
 */
class DecimalSafetyNetTest {

	/** As Spring Boot builds it: the modules on the classpath (java.time) registered, the global settings untouched. */
	private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

	// ─── 1. Readers ──────────────────────────────────────────────────

	private static ReportService reports(List<Object[]> rows) throws Exception {
		Query query = mock(Query.class);
		when(query.setParameter(anyString(), any())).thenReturn(query);
		when(query.getResultList()).thenReturn(rows);
		EntityManager em = mock(EntityManager.class);
		when(em.createQuery(anyString())).thenReturn(query);
		ReportService service = new ReportService();
		set(service, ReportService.class, "em", em);
		return service;
	}

	@Test
	@DisplayName("Reports: the sold quantity, the stock and the movements keep their decimals (0.2, 4.8), never truncated; whole sums stay 12")
	void reportsKeepDecimals() throws Exception {
		List<Object[]> sales = Arrays.asList(new Object[] { "V H 52 1L", 3L, new BigDecimal("0.258"), 10.0, 1.9, 11.9, 0.0 },
				new Object[] { "Whole", 2L, new BigDecimal("12.000"), 10.0, 1.9, 11.9, 0.0 });
		List<SalesReportRowDTO> salesRows = reports(sales).getSalesReport(LocalDate.now(), LocalDate.now(), "ITEM");
		assertEquals("0.258", salesRows.get(0).getTotalQuantity().toPlainString());
		assertEquals("12", salesRows.get(1).getTotalQuantity().toPlainString(), "JSON 12, as in 2.2.0");
		assertEquals("12", MAPPER.valueToTree(salesRows.get(1)).get("totalQuantity").toString());

		List<Object[]> stock = Arrays.asList(new Object[] { "V H 52 1L", "VH52-1L", new BigDecimal("0.200"), 1L, 5.0 },
				new Object[] { "Empty", "E1", new BigDecimal("0.000"), 0L, 0.0 });
		List<StockReportRowDTO> stockRows = reports(stock).getStockReport("ITEM", false);
		assertEquals("0.2", stockRows.get(0).getCurrentQty().toPlainString());
		assertEquals("LOW", stockRows.get(0).getStatus(), "0.2 is in stock, below the minimum 1");
		assertEquals("OUT", stockRows.get(1).getStatus());
		assertEquals(1, reports(stock.subList(0, 1)).getStockReport("ITEM", true).size(), "0.2 < 1: below the minimum");

		List<Object[]> moves = Collections.singletonList(new Object[] { "VH52-1L", new BigDecimal("5.000"), new BigDecimal("0.200"), 2L });
		StockMovementReportRowDTO move = reports(moves).getStockMovementsReport(LocalDate.now(), LocalDate.now(), "ITEM", null).get(0);
		assertEquals("5", move.getQtyIn().toPlainString());
		assertEquals("0.2", move.getQtyOut().toPlainString());
		assertEquals("4.8", move.getNetQty().toPlainString());
	}

	@Test
	@DisplayName("Dashboard: the top products keep 0.2 sold (was a loud failure at step 1); a whole sum stays 3")
	void dashboardTopProducts() throws Exception {
		AnalyticsService analytics = new AnalyticsService();
		set(analytics, AnalyticsService.class, "analyticsRepository", new AnalyticsRepository() {
			@Override
			public List<Object[]> getTopProducts(LocalDate from, LocalDate to, int limit) {
				return Arrays.asList(new Object[] { "VH52-1L", "V H 52 1L", "Parfum", new BigDecimal("0.200"), new BigDecimal("10") },
						new Object[] { "B001", "Whole", "Parfum", new BigDecimal("3.000"), new BigDecimal("30") });
			}
		});
		List<TopProductDTO> top = analytics.getTopProducts(LocalDate.now(), LocalDate.now(), 5);
		assertEquals("0.2", top.get(0).getQuantitySold().toPlainString());
		assertEquals("3", MAPPER.valueToTree(top.get(1)).get("quantitySold").toString());
	}

	@Test
	@DisplayName("Return screen: a line sold with decimals is listed with its quantity, not returnable, the reason naming the item")
	@SuppressWarnings("unchecked")
	void returnScreenLine() throws Exception {
		Item item = new Item();
		item.setId(3L);
		item.setItemCode("VH52-1L");
		item.setName("V H 52 1L");
		SalesLine line = new SalesLine();
		line.setId(9L);
		line.setItem(item);
		line.setQuantity(new BigDecimal("0.2"));
		Method notReturnable = ReturnHeaderAPI.class.getDeclaredMethod("notReturnable", SalesLine.class);
		notReturnable.setAccessible(true);
		Map<String, Object> listed = (Map<String, Object>) notReturnable.invoke(null, line);
		assertEquals(new BigDecimal("0.2"), listed.get("quantity"));
		assertEquals(Boolean.FALSE, listed.get("returnable"));
		assertEquals(0, listed.get("remainingQuantity"));
		assertEquals("Item VH52-1L (V H 52 1L): the quantity 0.2 has decimals, and decimal quantities are not supported in"
				+ " returns yet.", listed.get("notReturnableReason"));
	}

	// ─── 2. Writers not converted ────────────────────────────────────

	@Test
	@DisplayName("Invoice from tickets: a decimal line refuses the invoice before anything is written, naming the ticket and the item")
	void invoiceRefusesDecimal() {
		SalesHeader ticket = new SalesHeader();
		ticket.setSalesNumber("T-20261010-0001");
		Item item = new Item();
		item.setItemCode("VH52-1L");
		item.setName("V H 52 1L");
		SalesLine whole = new SalesLine();
		whole.setSalesHeader(ticket);
		whole.setItem(item);
		whole.setQuantity(new BigDecimal("2"));
		SalesLine decimal = new SalesLine();
		decimal.setSalesHeader(ticket);
		decimal.setItem(item);
		decimal.setQuantity(new BigDecimal("0.058"));

		InvoiceService.refuseDecimalLines(Arrays.asList(whole)); // whole: as in 2.2.0
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> InvoiceService.refuseDecimalLines(Arrays.asList(whole, decimal)));
		assertEquals("Ticket T-20261010-0001: Item VH52-1L (V H 52 1L): the quantity 0.058 has decimals, and decimal"
				+ " quantities are not supported in invoices yet.", refused.getMessage());
	}

	@Test
	@DisplayName("Composition: 1.5 is never read as 1, the save refuses it naming the component; 2 and 2.0 are 2 as in 2.2.0")
	void compositionRefusesDecimal() throws Exception {
		ItemComposition decimal = MAPPER.readValue("{\"parentItem\":{\"id\":1},\"componentItem\":{\"id\":2},\"quantity\":1.5}",
				ItemComposition.class);
		assertNull(decimal.getQuantity());
		assertEquals(new BigDecimal("1.5"), decimal.getDecimalQuantity());
		assertEquals(Integer.valueOf(2), MAPPER.readValue("{\"quantity\":2.0}", ItemComposition.class).getQuantity());
		ItemComposition whole = MAPPER.readValue("{\"quantity\":2}", ItemComposition.class);
		assertEquals(Integer.valueOf(2), whole.getQuantity());
		assertNull(whole.getDecimalQuantity());
		assertFalse(MAPPER.writeValueAsString(whole).contains("decimalQuantity"), "never written in JSON");
		assertTrue(MAPPER.writeValueAsString(whole).contains("\"quantity\":2"));

		ItemRepository items = mock(ItemRepository.class);
		Item component = new Item();
		component.setId(2L);
		component.setItemCode("FLACON-50");
		component.setName("Flacon 50 ml");
		component.setType(ItemType.PRODUCT);
		when(items.findById(2L)).thenReturn(Optional.of(component));
		ItemCompositionService service = new ItemCompositionService();
		set(service, ItemCompositionService.class, "itemRepository", items);
		set(service, ItemCompositionService.class, "itemCompositionRepository", mock(ItemCompositionRepository.class));
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.save(decimal));
		String text = "Item FLACON-50 (Flacon 50 ml): the quantity 1.5 has decimals, and decimal quantities are not"
				+ " supported in compositions yet.";
		assertEquals(text, refused.getMessage());

		com.digithink.zsretail.controller.ItemCompositionAPI api = new com.digithink.zsretail.controller.ItemCompositionAPI();
		set(api, com.digithink.zsretail.controller._BaseController.class, "service", service);
		set(api, com.digithink.zsretail.controller.ItemCompositionAPI.class, "itemCompositionService", service);
		org.springframework.http.ResponseEntity<?> created = api.create(decimal);
		assertEquals(400, created.getStatusCodeValue());
		assertEquals(text, created.getBody());
		assertEquals(400, api.update(8L, decimal).getStatusCodeValue());
	}

	// ─── 3. Excel item import ────────────────────────────────────────

	@Test
	@DisplayName("Item import: a stock cell not whole is refused with its row number (0.25 was read as 25, text 0.250 as 250); whole cells read as in 2.2.0")
	void importRefusesDecimalStock() throws Exception {
		ItemRepository items = mock(ItemRepository.class);
		List<Item> saved = new ArrayList<>();
		when(items.findByItemCode(anyString())).thenReturn(Optional.empty());
		when(items.save(any(Item.class))).thenAnswer(a -> {
			saved.add(a.getArgument(0));
			return a.getArgument(0);
		});
		@SuppressWarnings("unchecked")
		ObjectProvider<CatalogueHeadOfficeHooks> hooks = mock(ObjectProvider.class);
		DataImportService service = new DataImportService(mock(ItemFamilyRepository.class),
				mock(ItemSubFamilyRepository.class), items, mock(ItemBarcodeRepository.class), mock(VendorRepository.class),
				mock(CustomerRepository.class), mock(LocationRepository.class), mock(SalesPriceRepository.class),
				mock(SalesDiscountRepository.class), hooks);

		byte[] file;
		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet();
			header(sheet.createRow(0), "Code", "Name", "Price", "Stock");
			item(sheet.createRow(1), "A1", 12.0, null);       // row 2: number 12
			item(sheet.createRow(2), "A2", 0.25, null);       // row 3: number 0.25, was 25
			item(sheet.createRow(3), "A3", null, "0.250");    // row 4: text 0.250, was 250
			item(sheet.createRow(4), "A4", null, "1.000");    // row 5: text with a thousands separator: 1000, as in 2.2.0
			item(sheet.createRow(5), "A5", 1.5, null);        // row 6: number 1.5, was 15
			item(sheet.createRow(6), "A6", null, "7");        // row 7: text 7
			item(sheet.createRow(7), "A7", null, "1,5");      // row 8: text 1,5, was 15
			workbook.write(out);
			file = out.toByteArray();
		}
		List<ImportFieldMappingDTO> mapping = Arrays.asList(new ImportFieldMappingDTO("itemCode", "Code"),
				new ImportFieldMappingDTO("name", "Name"), new ImportFieldMappingDTO("unitPrice", "Price"),
				new ImportFieldMappingDTO("stockQuantity", "Stock"));
		ImportResultDTO result = service.executeImport(new MockMultipartFile("file", "items.xlsx", null, file), "ITEMS",
				mapping);

		assertEquals(7, result.getTotalRows());
		assertEquals(3, result.getSuccessCount());
		assertEquals(Arrays.asList(3, 4, 6, 8), Arrays.asList(result.getErrors().stream().map(ImportResultDTO.RowError::getRow)
				.toArray()));
		assertEquals("Stock quantity '0.25' is not a whole number: decimal stock quantities are not supported in the item"
				+ " import yet, the row is not imported.", result.getErrors().get(0).getMessage());
		assertTrue(result.getErrors().get(1).getMessage().startsWith("Stock quantity '0.250' is not a whole number"));
		assertEquals(Arrays.asList("A1=12", "A4=1000", "A6=7"), Arrays.asList(saved.stream()
				.map(i -> i.getItemCode() + "=" + i.getStockQuantity().toPlainString()).toArray()));
	}

	private static void header(Row row, String... names) {
		for (int i = 0; i < names.length; i++) {
			row.createCell(i).setCellValue(names[i]);
		}
	}

	private static void item(Row row, String code, Double stockNumber, String stockText) {
		row.createCell(0).setCellValue(code);
		row.createCell(1).setCellValue("Item " + code);
		row.createCell(2).setCellValue(10.0);
		if (stockNumber != null) {
			row.createCell(3).setCellValue(stockNumber);
		} else {
			row.createCell(3).setCellValue(stockText);
		}
	}

	// ─── 4. Promotions ───────────────────────────────────────────────

	private static Promotion promotion(PromotionBenefitType benefit, Integer minimum) {
		Promotion p = new Promotion();
		p.setBenefitType(benefit);
		p.setMinimumQuantity(minimum);
		return p;
	}

	@Test
	@DisplayName("Item promotions: a decimal line is not eligible with a minimum quantity or a free quantity; percentage and fixed without a minimum as in 2.2.0")
	void itemPromotionEligibility() {
		BigDecimal decimal = new BigDecimal("2.5");
		assertFalse(PromotionCalculationService.passesQuantityFilter(promotion(PromotionBenefitType.PERCENTAGE_DISCOUNT, 2), decimal));
		assertFalse(PromotionCalculationService.passesQuantityFilter(promotion(PromotionBenefitType.FREE_QUANTITY, 2), decimal));
		assertFalse(PromotionCalculationService.passesQuantityFilter(promotion(PromotionBenefitType.FREE_QUANTITY, null), decimal));
		assertTrue(PromotionCalculationService.passesQuantityFilter(promotion(PromotionBenefitType.PERCENTAGE_DISCOUNT, null), decimal));
		assertTrue(PromotionCalculationService.passesQuantityFilter(promotion(PromotionBenefitType.FIXED_DISCOUNT, null), decimal));
		assertTrue(PromotionCalculationService.passesQuantityFilter(promotion(PromotionBenefitType.FIXED_DISCOUNT, 0), decimal));
		// whole quantities: as in 2.2.0
		assertTrue(PromotionCalculationService.passesQuantityFilter(promotion(PromotionBenefitType.FREE_QUANTITY, 2), new BigDecimal("2")));
		assertTrue(PromotionCalculationService.passesQuantityFilter(promotion(PromotionBenefitType.FREE_QUANTITY, 2), new BigDecimal("3.000")));
		assertFalse(PromotionCalculationService.passesQuantityFilter(promotion(PromotionBenefitType.PERCENTAGE_DISCOUNT, 3), new BigDecimal("2")));
	}

	@Test
	@DisplayName("Buy X get Y: a decimal buy line never counts (2.5 L earns nothing, never a free item on a fraction); whole lines as in 2.2.0")
	void crossProductIgnoresDecimalLines() throws Exception {
		Item bulk = new Item();
		bulk.setId(1L);
		Item gift = new Item();
		gift.setId(2L);
		gift.setItemCode("GIFT");
		Item whole = new Item();
		whole.setId(3L);
		Promotion buy2get1 = new Promotion();
		buy2get1.setId(50L);
		buy2get1.setName("Buy 2 get 1");
		buy2get1.setPromotionType(PromotionType.QUANTITY_PROMOTION);
		buy2get1.setScope(PromotionScope.ALL_ITEMS);
		buy2get1.setMinimumQuantity(2);
		buy2get1.setBenefitType(PromotionBenefitType.FREE_QUANTITY);
		buy2get1.setFreeQuantity(1);
		buy2get1.setGetItem(gift);

		PromotionRepository promotions = mock(PromotionRepository.class);
		when(promotions.findActiveCrossProductPromotions(any())).thenReturn(Collections.singletonList(buy2get1));
		ItemRepository items = mock(ItemRepository.class);
		when(items.findAllById(any())).thenReturn(Arrays.asList(bulk, whole));
		PromotionCalculationService service = new PromotionCalculationService();
		set(service, PromotionCalculationService.class, "promotionRepository", promotions);
		set(service, PromotionCalculationService.class, "itemRepository", items);

		CartCalculateResponseDTO decimalOnly = service.calculateCartPromotion(null,
				Collections.singletonList(cartLine(1L, "2.5")), null);
		assertTrue(decimalOnly.getCrossProductAdjustments().isEmpty(), "2.5 L earns nothing");

		CartCalculateResponseDTO mixed = service.calculateCartPromotion(null,
				Arrays.asList(cartLine(1L, "4.5"), cartLine(3L, "4")), null);
		assertEquals(1, mixed.getCrossProductAdjustments().size());
		assertEquals(Integer.valueOf(2), mixed.getCrossProductAdjustments().get(0).getEntitledUnits(), "the 4 whole only");
		assertEquals(Integer.valueOf(2), mixed.getCrossProductAdjustments().get(0).getFreeUnits());
	}

	private static CartCalculateRequestDTO.CartItemDTO cartLine(Long itemId, String quantity) {
		CartCalculateRequestDTO.CartItemDTO line = new CartCalculateRequestDTO.CartItemDTO();
		line.setItemId(itemId);
		line.setQuantity(new BigDecimal(quantity));
		return line;
	}

	// ─── 5. The scoped guard ─────────────────────────────────────────

	@Test
	@DisplayName("Guard: a whole-number quantity field refuses 1.5 naming the field; 2, 2.0, \"2\" and null read as in 2.2.0")
	void guardOnWholeFields() throws Exception {
		NotWholeQuantity free = assertThrows(NotWholeQuantity.class, () -> MAPPER.readValue(
				"{\"lines\":[{\"itemId\":1,\"quantity\":2},{\"itemId\":2,\"quantity\":3,\"freeQuantity\":1.5}]}",
				ProcessSaleRequestDTO.class));
		assertEquals("lines[1].freeQuantity", free.getField());
		assertEquals("lines[1].freeQuantity: 1.5 is not a whole number, and decimal quantities are not supported here yet.",
				free.getOriginalMessage());

		ProcessSaleRequestDTO whole = MAPPER.readValue(
				"{\"lines\":[{\"freeQuantity\":2},{\"freeQuantity\":2.0},{\"freeQuantity\":\"2\"},{\"freeQuantity\":null}]}",
				ProcessSaleRequestDTO.class);
		assertEquals(Integer.valueOf(2), whole.getLines().get(0).getFreeQuantity());
		assertEquals(Integer.valueOf(2), whole.getLines().get(1).getFreeQuantity());
		assertEquals(Integer.valueOf(2), whole.getLines().get(2).getFreeQuantity());
		assertNull(whole.getLines().get(3).getFreeQuantity());

		assertEquals("minimumQuantity", assertThrows(NotWholeQuantity.class,
				() -> MAPPER.readValue("{\"minimumQuantity\":2.5}", Promotion.class)).getField());
		assertEquals("freeQuantity", assertThrows(NotWholeQuantity.class,
				() -> MAPPER.readValue("{\"freeQuantity\":0.5}", PromotionCopyDTO.class)).getField());
		assertEquals("quantity", assertThrows(NotWholeQuantity.class,
				() -> MAPPER.readValue("{\"quantity\":1.25}", ReturnLineCopyDTO.class)).getField());
		assertEquals("cashCountLines[0].quantity", assertThrows(NotWholeQuantity.class,
				() -> MAPPER.readValue("{\"cashCountLines\":[{\"quantity\":3.5}]}", CloseSessionRequestDTO.class)).getField());
		assertEquals(Integer.valueOf(3), MAPPER.readValue("{\"minimumQuantity\":3.0}", Promotion.class).getMinimumQuantity());
	}

	@Test
	@DisplayName("Guard scoped: Jackson's global setting unchanged, a field without the guard reads 1.5 as 1 as in 2.2.0 (loyalty points)")
	void globalSettingUnchanged() throws Exception {
		assertEquals(Integer.valueOf(1), MAPPER.readValue("{\"delta\":1.5}", LoyaltyAdjustmentRequestDTO.class).getDelta());
	}

	@Test
	@DisplayName("Guard on a request: 400 whose body names the field; any other exception left to Spring (null)")
	void guardAnswers400() throws Exception {
		NotWholeQuantity cause = assertThrows(NotWholeQuantity.class,
				() -> MAPPER.readValue("{\"minimumQuantity\":2.5}", Promotion.class));
		HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error: " + cause.getMessage(),
				cause, new MockHttpInputMessage(new byte[0]));
		MockHttpServletResponse response = new MockHttpServletResponse();
		WholeQuantityExceptionResolver resolver = new WholeQuantityExceptionResolver();
		assertTrue(resolver.resolveException(new MockHttpServletRequest("POST", "/promotion"), response, null, ex) != null);
		assertEquals(400, response.getStatus());
		assertEquals("minimumQuantity: 2.5 is not a whole number, and decimal quantities are not supported here yet.",
				response.getContentAsString());

		HttpMessageNotReadableException other = new HttpMessageNotReadableException("JSON parse error",
				new MockHttpInputMessage(new byte[0]));
		assertNull(resolver.resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(), null, other));
		assertNull(resolver.resolveException(new MockHttpServletRequest(), new MockHttpServletResponse(), null,
				new IllegalStateException("x")));
		assertEquals(Integer.MIN_VALUE, resolver.getOrder());
	}

	private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
