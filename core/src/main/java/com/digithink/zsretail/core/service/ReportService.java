package com.digithink.zsretail.core.service;

import static com.digithink.zsretail.core.service.ReportValues.str;
import static com.digithink.zsretail.core.service.ReportValues.toDouble;
import static com.digithink.zsretail.core.service.ReportValues.toFrom;
import static com.digithink.zsretail.core.service.ReportValues.toLong;
import static com.digithink.zsretail.core.service.ReportValues.toQuantity;
import static com.digithink.zsretail.core.service.ReportValues.toTo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.dto.report.StockMovementReportRowDTO;
import com.digithink.zsretail.core.dto.report.StockReportRowDTO;
import com.digithink.zsretail.core.utils.Quantities;

import lombok.extern.log4j.Log4j2;

/**
 * Reporting service — all GROUP BY aggregation queries for the reporting module.
 * Uses JPQL via EntityManager to avoid @OneToMany collections on SalesHeader/Payment.
 * Queries traverse from child → parent (SalesLine → SalesHeader, Payment → SalesHeader).
 * DAY/MONTH grouping uses YEAR()/MONTH()/DAY() — standard JPQL, no vendor-specific FORMAT().
 * Code cleanup step 5: the stock reports, which a head office shows too; the store's reports (sales, purchases,
 * loyalty, sessions, promotions) are in SalesReportService.
 */
@Service
@Log4j2
@SuppressWarnings("unchecked")
public class ReportService {

    @PersistenceContext
    private EntityManager em;

    // ─────────────────────────────────────────────────────────────────────────
    // STOCK REPORT (snapshot — no date range)
    // groupBy: ITEM | FAMILY | SUBFAMILY
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<StockReportRowDTO> getStockReport(String groupBy, Boolean belowMinStock) {
        List<StockReportRowDTO> result = new ArrayList<>();

        switch (groupBy.toUpperCase()) {
            case "FAMILY": {
                String jpql =
                        "SELECT COALESCE(f.name, 'No Family'), " +
                        "SUM(COALESCE(i.stockQuantity, 0)), " +
                        "SUM(COALESCE(i.minStockLevel, 0)), " +
                        "SUM(COALESCE(i.stockQuantity, 0) * COALESCE(i.costPrice, 0)) " +
                        "FROM Item i LEFT JOIN i.itemFamily f " +
                        "WHERE i.active = true " +
                        "GROUP BY f.name ORDER BY f.name ASC";
                for (Object[] r : (List<Object[]>) em.createQuery(jpql).getResultList()) {
                    BigDecimal qty = toQuantity(r[1]);
                    long min = toLong(r[2]);
                    if (Boolean.TRUE.equals(belowMinStock) && qty.compareTo(BigDecimal.valueOf(min)) >= 0) continue;
                    result.add(new StockReportRowDTO(str(r[0]), null, qty, min, toDouble(r[3]), stockStatus(qty, min)));
                }
                break;
            }
            case "SUBFAMILY": {
                String jpql =
                        "SELECT COALESCE(sf.name, 'No Subfamily'), " +
                        "SUM(COALESCE(i.stockQuantity, 0)), " +
                        "SUM(COALESCE(i.minStockLevel, 0)), " +
                        "SUM(COALESCE(i.stockQuantity, 0) * COALESCE(i.costPrice, 0)) " +
                        "FROM Item i LEFT JOIN i.itemSubFamily sf " +
                        "WHERE i.active = true " +
                        "GROUP BY sf.name ORDER BY sf.name ASC";
                for (Object[] r : (List<Object[]>) em.createQuery(jpql).getResultList()) {
                    BigDecimal qty = toQuantity(r[1]);
                    long min = toLong(r[2]);
                    if (Boolean.TRUE.equals(belowMinStock) && qty.compareTo(BigDecimal.valueOf(min)) >= 0) continue;
                    result.add(new StockReportRowDTO(str(r[0]), null, qty, min, toDouble(r[3]), stockStatus(qty, min)));
                }
                break;
            }
            default: { // ITEM
                String jpql =
                        "SELECT i.name, i.itemCode, " +
                        "COALESCE(i.stockQuantity, 0), " +
                        "COALESCE(i.minStockLevel, 0), " +
                        "COALESCE(i.stockQuantity, 0) * COALESCE(i.costPrice, 0) " +
                        "FROM Item i WHERE i.active = true ORDER BY i.name ASC";
                for (Object[] r : (List<Object[]>) em.createQuery(jpql).getResultList()) {
                    BigDecimal qty = toQuantity(r[2]);
                    long min = toLong(r[3]);
                    if (Boolean.TRUE.equals(belowMinStock) && qty.compareTo(BigDecimal.valueOf(min)) >= 0) continue;
                    result.add(new StockReportRowDTO(str(r[0]), str(r[1]), qty, min, toDouble(r[4]), stockStatus(qty, min)));
                }
                break;
            }
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STOCK MOVEMENTS REPORT
    // groupBy: ITEM | FAMILY | TYPE | DAY | MONTH
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<StockMovementReportRowDTO> getStockMovementsReport(
            LocalDate dateFrom, LocalDate dateTo, String groupBy, String movementType) {
        LocalDateTime from = toFrom(dateFrom);
        LocalDateTime to   = toTo(dateTo);

        String selectLabel;
        String groupByClause;
        String extraJoin = "";

        switch (groupBy.toUpperCase()) {
            case "FAMILY":
                extraJoin     = "JOIN sm.item i LEFT JOIN i.itemFamily f ";
                selectLabel   = "COALESCE(f.name, 'No Family')";
                groupByClause = "f.name";
                break;
            case "TYPE":
                selectLabel   = "CAST(sm.movementType AS string)";
                groupByClause = "sm.movementType";
                break;
            case "DAY":
                selectLabel   = "CONCAT(CAST(YEAR(sm.createdAt) AS string), '-', CAST(MONTH(sm.createdAt) AS string), '-', CAST(DAY(sm.createdAt) AS string))";
                groupByClause = "YEAR(sm.createdAt), MONTH(sm.createdAt), DAY(sm.createdAt)";
                break;
            case "MONTH":
                selectLabel   = "CONCAT(CAST(YEAR(sm.createdAt) AS string), '-', CAST(MONTH(sm.createdAt) AS string))";
                groupByClause = "YEAR(sm.createdAt), MONTH(sm.createdAt)";
                break;
            default: // ITEM
                extraJoin     = "JOIN sm.item i ";
                selectLabel   = "CONCAT(i.itemCode, ' - ', i.name)";
                groupByClause = "i.itemCode, i.name";
                break;
        }

        boolean hasTypeFilter = movementType != null && !movementType.isBlank();
        String typeFilter = hasTypeFilter ? "AND CAST(sm.movementType AS string) = :movementType " : "";

        String jpql =
                "SELECT " + selectLabel + ", " +
                "SUM(CASE WHEN sm.direction = 'IN'  THEN sm.quantity ELSE 0 END), " +
                "SUM(CASE WHEN sm.direction = 'OUT' THEN sm.quantity ELSE 0 END), " +
                "COUNT(sm.id) " +
                "FROM StockMovement sm " +
                extraJoin +
                "WHERE sm.createdAt BETWEEN :from AND :to " +
                typeFilter +
                "GROUP BY " + groupByClause + " " +
                "ORDER BY SUM(sm.quantity) DESC";

        Query query = em.createQuery(jpql)
                .setParameter("from", from)
                .setParameter("to", to);
        if (hasTypeFilter) query.setParameter("movementType", movementType);

        List<StockMovementReportRowDTO> result = new ArrayList<>();
        for (Object[] r : (List<Object[]>) query.getResultList()) {
            BigDecimal qtyIn = toQuantity(r[1]), qtyOut = toQuantity(r[2]);
            result.add(new StockMovementReportRowDTO(str(r[0]), qtyIn, qtyOut, Quantities.normalize(qtyIn.subtract(qtyOut)),
                    toLong(r[3])));
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String stockStatus(BigDecimal qty, long min) {
        if (qty.signum() <= 0)  return "OUT"; // 2.2.1: 0.2 is in stock
        if (qty.compareTo(BigDecimal.valueOf(min)) < 0) return "LOW";
        return "OK";
    }
}
