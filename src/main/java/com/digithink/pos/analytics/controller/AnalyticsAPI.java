package com.digithink.pos.analytics.controller;

import com.digithink.pos.analytics.dto.AnalyticsSummaryDTO;
import com.digithink.pos.analytics.dto.PaymentBreakdownDTO;
import com.digithink.pos.analytics.dto.SalesTrendPointDTO;
import com.digithink.pos.analytics.dto.TopProductDTO;
import com.digithink.pos.analytics.service.AnalyticsService;
import com.digithink.pos.security.CurrentUserProvider;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("admin/analytics")
@RequiredArgsConstructor
@Log4j2
public class AnalyticsAPI {

	private final AnalyticsService analyticsService;

	private static LocalDate parseLocalDate(String value) {
		try {
			return LocalDate.parse(value); // expects yyyy-MM-dd (ISO_LOCAL_DATE)
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("Invalid date: '" + value + "'. Expected yyyy-MM-dd.", e);
		}
	}

	@GetMapping("/summary")
	public ResponseEntity<?> getSummary(
			@RequestParam String from,
			@RequestParam String to) {
		try {
			LocalDate fromDate = parseLocalDate(from);
			LocalDate toDate = parseLocalDate(to);
			AnalyticsSummaryDTO dto = analyticsService.getSummary(fromDate, toDate);
			return ResponseEntity.ok(dto);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		} catch (Exception e) {
			log.error("AnalyticsAPI::getSummary error: {}", e.getMessage(), e);
			return ResponseEntity.status(500).body("Failed to load analytics summary");
		}
	}

	@GetMapping("/sales/trend")
	public ResponseEntity<?> getSalesTrend(
			@RequestParam String from,
			@RequestParam String to) {
		try {
			LocalDate fromDate = parseLocalDate(from);
			LocalDate toDate = parseLocalDate(to);
			List<SalesTrendPointDTO> trend = analyticsService.getSalesTrend(fromDate, toDate);
			return ResponseEntity.ok(trend);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		} catch (Exception e) {
			log.error("AnalyticsAPI::getSalesTrend error: {}", e.getMessage(), e);
			return ResponseEntity.status(500).body("Failed to load sales trend");
		}
	}

	@GetMapping("/products/top")
	public ResponseEntity<?> getTopProducts(
			@RequestParam String from,
			@RequestParam String to,
			@RequestParam(defaultValue = "5") int limit) {
		try {
			if (limit > 10) {
				limit = 10; // MVP safety clamp
			} else if (limit < 1) {
				return ResponseEntity.badRequest().body("limit must be >= 1");
			}

			LocalDate fromDate = parseLocalDate(from);
			LocalDate toDate = parseLocalDate(to);

			List<TopProductDTO> products = analyticsService.getTopProducts(fromDate, toDate, limit);
			return ResponseEntity.ok(products);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		} catch (Exception e) {
			log.error("AnalyticsAPI::getTopProducts error: {}", e.getMessage(), e);
			return ResponseEntity.status(500).body("Failed to load top products");
		}
	}

	@GetMapping("/payments/breakdown")
	public ResponseEntity<?> getPaymentBreakdown(
			@RequestParam String from,
			@RequestParam String to) {
		try {
			LocalDate fromDate = parseLocalDate(from);
			LocalDate toDate = parseLocalDate(to);
			List<PaymentBreakdownDTO> breakdown = analyticsService.getPaymentBreakdown(fromDate, toDate);
			return ResponseEntity.ok(breakdown);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		} catch (Exception e) {
			log.error("AnalyticsAPI::getPaymentBreakdown error: {}", e.getMessage(), e);
			return ResponseEntity.status(500).body("Failed to load payment breakdown");
		}
	}
}

