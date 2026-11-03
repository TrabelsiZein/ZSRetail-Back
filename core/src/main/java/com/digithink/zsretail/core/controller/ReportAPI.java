package com.digithink.zsretail.core.controller;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.report.StockCardDTO;
import com.digithink.zsretail.core.dto.report.StockMovementReportFilter;
import com.digithink.zsretail.core.dto.report.StockMovementReportRowDTO;
import com.digithink.zsretail.core.dto.report.StockReportFilter;
import com.digithink.zsretail.core.dto.report.StockReportRowDTO;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;
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
     * movements to count back from, no cost history). The rebuilt report: groupBy also CATEGORY / SECTION; the filters
     * search, familyId, subFamilyId, categoryCode, sectionCode, status (a list of OK, LOW, OUT, NEGATIVE, WITHOUT_COST,
     * any of them; 400 for another); belowMinStock=true adds LOW, OUT, NEGATIVE.
     */
    @GetMapping("/stock")
    public ResponseEntity<?> getStockReport(
            @RequestParam(defaultValue = "ITEM") String groupBy,
            @RequestParam(required = false) Boolean belowMinStock,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long familyId,
            @RequestParam(required = false) Long subFamilyId,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String sectionCode,
            @RequestParam(required = false) List<String> status) {
        StockReportFilter filter = StockReportFilter.belowMin(belowMinStock);
        filter.setSearch(text(search));
        filter.setFamilyId(familyId);
        filter.setSubFamilyId(subFamilyId);
        filter.setCategoryCode(text(categoryCode));
        filter.setSectionCode(text(sectionCode));
        for (String one : status == null ? List.<String>of() : status) {
            String value = one.trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            if (!StockReportFilter.STATUSES.contains(value)) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "Unknown status " + one + ": " + String.join(", ", StockReportFilter.STATUSES) + "."));
            }
            filter.getStatuses().add(value);
        }
        if (date != null && date.isAfter(LocalDate.now())) {
            return ResponseEntity.badRequest().body(Map.of("error", "The date cannot be in the future."));
        }
        if (date != null && date.isBefore(LocalDate.now()) && !applicationModeService.keepsOwnStock()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "The stock value at a past date needs the stock kept here: this installation keeps no stock of its own."));
        }
        try {
            List<StockReportRowDTO> data = reportService.getStockReport(groupBy, filter, date);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error generating stock report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 3.1.2: an item's stock card over a period (the movements report's row opens it): its stock at the start and the
     * end, every movement in time order with the stock after it, the documents' numbers, who made them; at most
     * {@link ReportService#STOCK_CARD_MAX} lines (the latest). 404 for an unknown item, 400 for dateFrom after dateTo.
     */
    @GetMapping("/stock-movements/item/{itemId}")
    public ResponseEntity<?> getStockCard(@PathVariable Long itemId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            return ResponseEntity.badRequest().body(Map.of("error", "The start date is after the end date."));
        }
        try {
            StockCardDTO card = reportService.getStockCard(itemId, dateFrom, dateTo, ReportService.STOCK_CARD_MAX);
            if (card == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown item " + itemId + "."));
            }
            return ResponseEntity.ok(card);
        } catch (Exception e) {
            log.error("Error reading the stock card of item {}", itemId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    /** A blank filter is none. */
    private static String text(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    /**
     * The stock movements report. 3.1.2 rebuild: groupBy also SUBFAMILY / CATEGORY / SECTION; the filters search,
     * familyId, subFamilyId, categoryCode, sectionCode, types (a list of movement types, any of them; movementType, one
     * type, still read). 400 for an unknown type and for dateFrom after dateTo. COST_ADJUSTMENT is never counted.
     */
    @GetMapping("/stock-movements")
    public ResponseEntity<?> getStockMovementsReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "ITEM") String groupBy,
            @RequestParam(required = false) String movementType,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long familyId,
            @RequestParam(required = false) Long subFamilyId,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String sectionCode,
            @RequestParam(required = false) List<String> types) {
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            return ResponseEntity.badRequest().body(Map.of("error", "The start date is after the end date."));
        }
        StockMovementReportFilter filter = new StockMovementReportFilter();
        filter.setSearch(text(search));
        filter.setFamilyId(familyId);
        filter.setSubFamilyId(subFamilyId);
        filter.setCategoryCode(text(categoryCode));
        filter.setSectionCode(text(sectionCode));
        List<String> asked = new ArrayList<>(types == null ? List.of() : types);
        if (movementType != null) asked.add(movementType);
        for (String one : asked) {
            String value = one.trim().toUpperCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            try {
                filter.getTypes().add(StockMovementType.valueOf(value));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown movement type " + one + "."));
            }
        }
        try {
            List<StockMovementReportRowDTO> data = reportService.getStockMovementsReport(dateFrom, dateTo, groupBy, filter);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error generating stock movements report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}
