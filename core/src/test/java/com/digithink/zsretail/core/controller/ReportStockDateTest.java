package com.digithink.zsretail.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.service.ReportService;
import com.digithink.zsretail.core.support.TestModes;

/**
 * 3.1.2, the stock value at a date: GET /report/stock?date. A date after today 400; a past date 400 where the
 * installation keeps no stock of its own (the ERP keeps it, a head office without stock); a past date elsewhere and
 * today (or none) everywhere go to the report as before.
 */
class ReportStockDateTest {

	private final ReportService reports = mock(ReportService.class);

	private ReportAPI api(ApplicationModeService mode) {
		when(reports.getStockReport(anyString(), any(), any())).thenReturn(Collections.emptyList());
		return new ReportAPI(reports, mode);
	}

	@Test
	@DisplayName("A date after today: 400, no report")
	void future() {
		assertEquals(400, api(TestModes.standalone()).getStockReport("ITEM", null, LocalDate.now().plusDays(1))
				.getStatusCodeValue());
		verify(reports, never()).getStockReport(anyString(), any(), any());
	}

	@Test
	@DisplayName("A past date where the installation keeps no stock of its own: 400")
	void pastWithoutOwnStock() {
		LocalDate past = LocalDate.now().minusDays(3);
		assertEquals(400, api(TestModes.erp()).getStockReport("ITEM", null, past).getStatusCodeValue());
		MockEnvironment headOfficeWithoutStock = new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")
				.withProperty("headoffice.stock.enabled", "false");
		assertEquals(400, api(TestModes.of(headOfficeWithoutStock)).getStockReport("ITEM", null, past).getStatusCodeValue());
		verify(reports, never()).getStockReport(anyString(), any(), any());
	}

	@Test
	@DisplayName("A past date with the stock kept here, and today or no date anywhere: the report")
	void allowed() {
		LocalDate past = LocalDate.now().minusDays(3);
		assertEquals(200, api(TestModes.standalone()).getStockReport("FAMILY", true, past).getStatusCodeValue());
		verify(reports).getStockReport("FAMILY", true, past);
		assertEquals(200, api(TestModes.erp()).getStockReport("ITEM", null, LocalDate.now()).getStatusCodeValue());
		assertEquals(200, api(TestModes.erp()).getStockReport("ITEM", null, null).getStatusCodeValue());
	}
}
