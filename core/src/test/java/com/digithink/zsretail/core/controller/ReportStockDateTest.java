package com.digithink.zsretail.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.report.StockReportFilter;
import com.digithink.zsretail.core.service.ReportService;
import com.digithink.zsretail.core.support.TestModes;

/**
 * 3.1.2, the stock value at a date: GET /report/stock?date. A date after today 400; a past date 400 where the
 * installation keeps no stock of its own (the ERP keeps it, a head office without stock); a past date elsewhere and
 * today (or none) everywhere go to the report as before. The rebuilt report's filters: handed to the report as one
 * filter (blank ones none, belowMinStock its three statuses), an unknown status 400.
 */
class ReportStockDateTest {

	private final ReportService reports = mock(ReportService.class);

	private ReportAPI api(ApplicationModeService mode) {
		when(reports.getStockReport(anyString(), any(StockReportFilter.class), any())).thenReturn(Collections.emptyList());
		return new ReportAPI(reports, mode);
	}

	/** Without the rebuilt report's filters. */
	private static ResponseEntity<?> stock(ReportAPI api, String groupBy, Boolean belowMinStock, LocalDate date) {
		return api.getStockReport(groupBy, belowMinStock, date, null, null, null, null, null, null);
	}

	@Test
	@DisplayName("A date after today: 400, no report")
	void future() {
		assertEquals(400, stock(api(TestModes.standalone()), "ITEM", null, LocalDate.now().plusDays(1)).getStatusCodeValue());
		verify(reports, never()).getStockReport(anyString(), any(StockReportFilter.class), any());
	}

	@Test
	@DisplayName("A past date where the installation keeps no stock of its own: 400")
	void pastWithoutOwnStock() {
		LocalDate past = LocalDate.now().minusDays(3);
		assertEquals(400, stock(api(TestModes.erp()), "ITEM", null, past).getStatusCodeValue());
		MockEnvironment headOfficeWithoutStock = new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")
				.withProperty("headoffice.stock.enabled", "false");
		assertEquals(400, stock(api(TestModes.of(headOfficeWithoutStock)), "ITEM", null, past).getStatusCodeValue());
		verify(reports, never()).getStockReport(anyString(), any(StockReportFilter.class), any());
	}

	@Test
	@DisplayName("A past date with the stock kept here, and today or no date anywhere: the report")
	void allowed() {
		LocalDate past = LocalDate.now().minusDays(3);
		assertEquals(200, stock(api(TestModes.standalone()), "FAMILY", true, past).getStatusCodeValue());
		ArgumentCaptor<StockReportFilter> filter = ArgumentCaptor.forClass(StockReportFilter.class);
		verify(reports).getStockReport(eq("FAMILY"), filter.capture(), eq(past));
		assertEquals(new LinkedHashSet<>(StockReportFilter.BELOW_MIN), filter.getValue().getStatuses(),
				"below the minimum: LOW, OUT, NEGATIVE");
		assertEquals(200, stock(api(TestModes.erp()), "ITEM", null, LocalDate.now()).getStatusCodeValue());
		assertEquals(200, stock(api(TestModes.erp()), "ITEM", null, null).getStatusCodeValue());
	}

	@Test
	@DisplayName("The filters: handed as one filter, blanks none, the statuses upper-cased; an unknown status 400")
	void filters() {
		ReportAPI api = api(TestModes.standalone());
		List<String> statuses = Arrays.asList("low", " WITHOUT_COST ", "");
		assertEquals(200, api.getStockReport("CATEGORY", null, null, " vani ", 3L, null, "CAT1", "  ", statuses)
				.getStatusCodeValue());
		ArgumentCaptor<StockReportFilter> filter = ArgumentCaptor.forClass(StockReportFilter.class);
		verify(reports).getStockReport(eq("CATEGORY"), filter.capture(), eq(null));
		assertEquals("vani", filter.getValue().getSearch());
		assertEquals(Long.valueOf(3), filter.getValue().getFamilyId());
		assertNull(filter.getValue().getSubFamilyId());
		assertEquals("CAT1", filter.getValue().getCategoryCode());
		assertNull(filter.getValue().getSectionCode(), "a blank code: none");
		assertEquals(new LinkedHashSet<>(Arrays.asList("LOW", "WITHOUT_COST")), filter.getValue().getStatuses());

		assertEquals(400, api.getStockReport("ITEM", null, null, null, null, null, null, null, Arrays.asList("OK", "EMPTY"))
				.getStatusCodeValue());
		verify(reports, never()).getStockReport(eq("ITEM"), any(StockReportFilter.class), any());
	}
}
