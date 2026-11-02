package com.digithink.zsretail.core.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.report.StockMovementReportRowDTO;
import com.digithink.zsretail.core.dto.report.StockReportRowDTO;
import com.digithink.zsretail.core.service.ReportService;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * The stock reports, which a head office shows too. Code cleanup step 5: the store's reports (sales, purchases,
 * loyalty, promotions, sessions) are SalesReportAPI, under the same path report/.
 */
@RestController
@RequestMapping("report")
@RequiredArgsConstructor
@Log4j2
public class ReportAPI {

    private final ReportService reportService;

    private final ApplicationModeService applicationModeService;

    /**
     * The stock report. 3.1.2: date (yyyy-MM-dd, optional) the stock value at the end of that day; none or today: the
     * stock now. 400 for a date after today, and for a past date where this installation keeps no stock of its own (no
     * movements to count back from, no cost history).
     */
    @GetMapping("/stock")
    public ResponseEntity<?> getStockReport(
            @RequestParam(defaultValue = "ITEM") String groupBy,
            @RequestParam(required = false) Boolean belowMinStock,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date != null && date.isAfter(LocalDate.now())) {
            return ResponseEntity.badRequest().body(Map.of("error", "The date cannot be in the future."));
        }
        if (date != null && date.isBefore(LocalDate.now()) && !applicationModeService.keepsOwnStock()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "The stock value at a past date needs the stock kept here: this installation keeps no stock of its own."));
        }
        try {
            List<StockReportRowDTO> data = reportService.getStockReport(groupBy, belowMinStock, date);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error generating stock report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/stock-movements")
    public ResponseEntity<?> getStockMovementsReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "ITEM") String groupBy,
            @RequestParam(required = false) String movementType) {
        try {
            List<StockMovementReportRowDTO> data = reportService.getStockMovementsReport(dateFrom, dateTo, groupBy, movementType);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error generating stock movements report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}
