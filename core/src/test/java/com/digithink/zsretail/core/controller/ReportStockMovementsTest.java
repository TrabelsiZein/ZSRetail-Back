package com.digithink.zsretail.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.digithink.zsretail.core.dto.report.StockCardDTO;
import com.digithink.zsretail.core.dto.report.StockMovementReportFilter;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;
import com.digithink.zsretail.core.service.ReportService;
import com.digithink.zsretail.core.support.TestModes;

/**
 * 3.1.2, GET /report/stock-movements rebuilt: the filters handed to the report as one (blank ones none, the types and
 * the movementType of before together), 400 for an unknown type and for a start after the end.
 */
class ReportStockMovementsTest {

	private final ReportService reports = mock(ReportService.class);

	private ReportAPI api() {
		when(reports.getStockMovementsReport(any(), any(), any(), any(StockMovementReportFilter.class))).thenReturn(Collections.emptyList());
		return new ReportAPI(reports, TestModes.standalone());
	}

	@Test
	@DisplayName("The filters: one filter, blanks none, the types upper-cased with the movementType of before")
	void filters() {
		LocalDate from = LocalDate.of(2026, 10, 1);
		LocalDate to = LocalDate.of(2026, 10, 31);
		assertEquals(200, api().getStockMovementsReport(from, to, "TYPE", "sale", " vani ", 3L, null, "CAT1", " ",
				Arrays.asList("inventory_in", "")).getStatusCodeValue());
		ArgumentCaptor<StockMovementReportFilter> filter = ArgumentCaptor.forClass(StockMovementReportFilter.class);
		verify(reports).getStockMovementsReport(eq(from), eq(to), eq("TYPE"), filter.capture());
		assertEquals("vani", filter.getValue().getSearch());
		assertEquals(Long.valueOf(3), filter.getValue().getFamilyId());
		assertEquals("CAT1", filter.getValue().getCategoryCode());
		assertEquals(null, filter.getValue().getSectionCode());
		assertEquals(new LinkedHashSet<>(Arrays.asList(StockMovementType.INVENTORY_IN, StockMovementType.SALE)), filter.getValue().getTypes());
	}

	@Test
	@DisplayName("400: an unknown type, a start after the end; no report")
	void refused() {
		LocalDate day = LocalDate.of(2026, 10, 10);
		assertEquals(400, api().getStockMovementsReport(null, null, "ITEM", null, null, null, null, null, null,
				Arrays.asList("SALE", "THEFT")).getStatusCodeValue());
		assertEquals(400, api().getStockMovementsReport(day, day.minusDays(1), "ITEM", null, null, null, null, null, null, null)
				.getStatusCodeValue());
		verify(reports, never()).getStockMovementsReport(any(), any(), any(), any(StockMovementReportFilter.class));
	}

	@Test
	@DisplayName("Stock card: 404 for an unknown item, 400 for a start after the end, the card with the lines' limit")
	void stockCard() {
		LocalDate day = LocalDate.of(2026, 10, 10);
		when(reports.getStockCard(eq(1L), any(), any(), anyInt())).thenReturn(new StockCardDTO());
		assertEquals(200, api().getStockCard(1L, day, day).getStatusCodeValue());
		verify(reports).getStockCard(1L, day, day, ReportService.STOCK_CARD_MAX);
		assertEquals(404, api().getStockCard(2L, day, day).getStatusCodeValue());
		assertEquals(400, api().getStockCard(1L, day, day.minusDays(1)).getStatusCodeValue());
	}
}
