package com.digithink.pos.controller;

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

import com.digithink.pos.dto.report.LoyaltyReportRowDTO;
import com.digithink.pos.dto.report.PromotionReportRowDTO;
import com.digithink.pos.dto.report.PurchaseReportRowDTO;
import com.digithink.pos.dto.report.SalesReportRowDTO;
import com.digithink.pos.dto.report.SessionReportRowDTO;
import com.digithink.pos.dto.report.StockMovementReportRowDTO;
import com.digithink.pos.dto.report.StockReportRowDTO;
import com.digithink.pos.model.UserAccount;
import com.digithink.pos.model.enumeration.Role;
import com.digithink.pos.security.CurrentUserProvider;
import com.digithink.pos.service.ReportService;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("report")
@RequiredArgsConstructor
@Log4j2
public class ReportAPI {

    private final ReportService reportService;
    private final CurrentUserProvider currentUserProvider;

    private boolean isAdmin() {
        try {
            UserAccount currentUser = currentUserProvider.getCurrentUser();
            return currentUser != null && currentUser.getRole() == Role.ADMIN;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isAdminOrResponsible() {
        try {
            UserAccount currentUser = currentUserProvider.getCurrentUser();
            return currentUser != null &&
                    (currentUser.getRole() == Role.ADMIN || currentUser.getRole() == Role.RESPONSIBLE);
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/sales")
    public ResponseEntity<?> getSalesReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "ITEM") String groupBy) {
        if (!isAdminOrResponsible()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            List<SalesReportRowDTO> data = reportService.getSalesReport(dateFrom, dateTo, groupBy);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error generating sales report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/purchases")
    public ResponseEntity<?> getPurchaseReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "ITEM") String groupBy) {
        if (!isAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            List<PurchaseReportRowDTO> data = reportService.getPurchaseReport(dateFrom, dateTo, groupBy);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error generating purchase report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/stock")
    public ResponseEntity<?> getStockReport(
            @RequestParam(defaultValue = "ITEM") String groupBy,
            @RequestParam(required = false) Boolean belowMinStock) {
        if (!isAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            List<StockReportRowDTO> data = reportService.getStockReport(groupBy, belowMinStock);
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
        if (!isAdmin()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            List<StockMovementReportRowDTO> data = reportService.getStockMovementsReport(dateFrom, dateTo, groupBy, movementType);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error generating stock movements report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/loyalty")
    public ResponseEntity<?> getLoyaltyReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "MEMBER") String groupBy,
            @RequestParam(required = false) String transactionType) {
        if (!isAdminOrResponsible()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            List<LoyaltyReportRowDTO> data = reportService.getLoyaltyReport(dateFrom, dateTo, groupBy, transactionType);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error generating loyalty report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/promotions")
    public ResponseEntity<?> getPromotionReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        if (!isAdminOrResponsible()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            List<PromotionReportRowDTO> data = reportService.getPromotionReport(dateFrom, dateTo);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error generating promotion report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> getSessionReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "CASHIER") String groupBy) {
        if (!isAdminOrResponsible()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try {
            List<SessionReportRowDTO> data = reportService.getSessionReport(dateFrom, dateTo, groupBy);
            return ResponseEntity.ok(data);
        } catch (Exception e) {
            log.error("Error generating session report", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }
}
