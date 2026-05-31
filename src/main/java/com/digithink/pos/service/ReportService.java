package com.digithink.pos.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.pos.dto.report.LoyaltyReportRowDTO;
import com.digithink.pos.dto.report.PromotionReportRowDTO;
import com.digithink.pos.dto.report.PurchaseReportRowDTO;
import com.digithink.pos.dto.report.SalesReportRowDTO;
import com.digithink.pos.dto.report.SessionReportRowDTO;
import com.digithink.pos.dto.report.StockMovementReportRowDTO;
import com.digithink.pos.dto.report.StockReportRowDTO;

import lombok.extern.log4j.Log4j2;

/**
 * Reporting service — all GROUP BY aggregation queries for the reporting module.
 * Uses JPQL via EntityManager to avoid @OneToMany collections on SalesHeader/Payment.
 * Queries traverse from child → parent (SalesLine → SalesHeader, Payment → SalesHeader).
 * DAY/MONTH grouping uses YEAR()/MONTH()/DAY() — standard JPQL, no vendor-specific FORMAT().
 */
@Service
@Log4j2
@SuppressWarnings("unchecked")
public class ReportService {

    @PersistenceContext
    private EntityManager em;

    // ─────────────────────────────────────────────────────────────────────────
    // SALES REPORT
    // groupBy: ITEM | FAMILY | SUBFAMILY | CUSTOMER | CASHIER | DAY | MONTH
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SalesReportRowDTO> getSalesReport(LocalDate dateFrom, LocalDate dateTo, String groupBy) {
        LocalDateTime from = toFrom(dateFrom);
        LocalDateTime to   = toTo(dateTo);

        String selectLabel;
        String groupByClause;
        String extraJoin = "";

        switch (groupBy.toUpperCase()) {
            case "FAMILY":
                extraJoin     = "JOIN sl.item i JOIN i.itemFamily f ";
                selectLabel   = "COALESCE(f.name, 'No Family')";
                groupByClause = "f.name";
                break;
            case "SUBFAMILY":
                extraJoin     = "JOIN sl.item i LEFT JOIN i.itemSubFamily sf ";
                selectLabel   = "COALESCE(sf.name, 'No Subfamily')";
                groupByClause = "sf.name";
                break;
            case "CUSTOMER":
                extraJoin     = "LEFT JOIN sl.salesHeader.customer c ";
                selectLabel   = "COALESCE(c.name, 'Walk-in')";
                groupByClause = "c.name";
                break;
            case "CASHIER":
                extraJoin     = "JOIN sl.salesHeader.cashierSession cs JOIN cs.cashier u ";
                selectLabel   = "u.username";
                groupByClause = "u.username";
                break;
            case "DAY":
                selectLabel   = "CONCAT(CAST(YEAR(sl.salesHeader.salesDate) AS string), '-', CAST(MONTH(sl.salesHeader.salesDate) AS string), '-', CAST(DAY(sl.salesHeader.salesDate) AS string))";
                groupByClause = "YEAR(sl.salesHeader.salesDate), MONTH(sl.salesHeader.salesDate), DAY(sl.salesHeader.salesDate)";
                break;
            case "MONTH":
                selectLabel   = "CONCAT(CAST(YEAR(sl.salesHeader.salesDate) AS string), '-', CAST(MONTH(sl.salesHeader.salesDate) AS string))";
                groupByClause = "YEAR(sl.salesHeader.salesDate), MONTH(sl.salesHeader.salesDate)";
                break;
            default: // ITEM
                extraJoin     = "JOIN sl.item i ";
                selectLabel   = "CONCAT(i.itemCode, ' - ', i.name)";
                groupByClause = "i.itemCode, i.name";
                break;
        }

        // Header-level discount is distributed proportionally to each line via discountPercentage.
        // discountPercentage is always stored (for fixed-amount discounts the frontend computes it).
        String hdrRate = "COALESCE(sl.salesHeader.discountPercentage, 0.0) / 100.0";
        String jpql =
                "SELECT " + selectLabel + ", " +
                "COUNT(DISTINCT sl.salesHeader.id), " +
                "SUM(sl.quantity), " +
                "SUM(sl.lineTotal                * (1.0 - " + hdrRate + ")), " +
                "SUM(COALESCE(sl.vatAmount, 0)   * (1.0 - " + hdrRate + ")), " +
                "SUM(sl.lineTotalIncludingVat    * (1.0 - " + hdrRate + ")), " +
                "SUM(COALESCE(sl.discountAmount, 0) + sl.lineTotalIncludingVat * " + hdrRate + ") " +
                "FROM SalesLine sl " +
                extraJoin +
                "WHERE sl.salesHeader.status = 'COMPLETED' " +
                "AND sl.salesHeader.salesDate BETWEEN :from AND :to " +
                "GROUP BY " + groupByClause + " " +
                "ORDER BY SUM(sl.lineTotalIncludingVat * (1.0 - " + hdrRate + ")) DESC";

        List<Object[]> rows = em.createQuery(jpql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        List<SalesReportRowDTO> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(new SalesReportRowDTO(
                    str(r[0]), toLong(r[1]), toLong(r[2]),
                    toDouble(r[3]), toDouble(r[4]), toDouble(r[5]), toDouble(r[6])));
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PURCHASE REPORT
    // groupBy: ITEM | FAMILY | VENDOR | DAY | MONTH
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PurchaseReportRowDTO> getPurchaseReport(LocalDate dateFrom, LocalDate dateTo, String groupBy) {
        LocalDateTime from = toFrom(dateFrom);
        LocalDateTime to   = toTo(dateTo);

        String selectLabel;
        String groupByClause;
        String extraJoin = "";

        switch (groupBy.toUpperCase()) {
            case "FAMILY":
                extraJoin     = "JOIN pl.item i JOIN i.itemFamily f ";
                selectLabel   = "COALESCE(f.name, 'No Family')";
                groupByClause = "f.name";
                break;
            case "VENDOR":
                extraJoin     = "LEFT JOIN pl.purchaseHeader.vendor v ";
                selectLabel   = "COALESCE(v.name, 'Unknown Vendor')";
                groupByClause = "v.name";
                break;
            case "DAY":
                selectLabel   = "CONCAT(CAST(YEAR(pl.purchaseHeader.purchaseDate) AS string), '-', CAST(MONTH(pl.purchaseHeader.purchaseDate) AS string), '-', CAST(DAY(pl.purchaseHeader.purchaseDate) AS string))";
                groupByClause = "YEAR(pl.purchaseHeader.purchaseDate), MONTH(pl.purchaseHeader.purchaseDate), DAY(pl.purchaseHeader.purchaseDate)";
                break;
            case "MONTH":
                selectLabel   = "CONCAT(CAST(YEAR(pl.purchaseHeader.purchaseDate) AS string), '-', CAST(MONTH(pl.purchaseHeader.purchaseDate) AS string))";
                groupByClause = "YEAR(pl.purchaseHeader.purchaseDate), MONTH(pl.purchaseHeader.purchaseDate)";
                break;
            default: // ITEM
                extraJoin     = "JOIN pl.item i ";
                selectLabel   = "CONCAT(i.itemCode, ' - ', i.name)";
                groupByClause = "i.itemCode, i.name";
                break;
        }

        String jpql =
                "SELECT " + selectLabel + ", " +
                "COUNT(DISTINCT pl.purchaseHeader.id), " +
                "SUM(pl.quantity), " +
                "SUM(pl.lineTotal), " +
                "SUM(COALESCE(pl.vatAmount, 0)), " +
                "SUM(COALESCE(pl.lineTotalIncludingVat, 0)) " +
                "FROM PurchaseLine pl " +
                extraJoin +
                "WHERE pl.purchaseHeader.purchaseDate BETWEEN :from AND :to " +
                "GROUP BY " + groupByClause + " " +
                "ORDER BY SUM(pl.lineTotal) DESC";

        List<Object[]> rows = em.createQuery(jpql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        List<PurchaseReportRowDTO> result = new ArrayList<>();
        for (Object[] r : rows) {
            result.add(new PurchaseReportRowDTO(
                    str(r[0]), toLong(r[1]), toLong(r[2]),
                    toDouble(r[3]), toDouble(r[4]), toDouble(r[5])));
        }
        return result;
    }

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
                    long qty = toLong(r[1]), min = toLong(r[2]);
                    if (Boolean.TRUE.equals(belowMinStock) && qty >= min) continue;
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
                    long qty = toLong(r[1]), min = toLong(r[2]);
                    if (Boolean.TRUE.equals(belowMinStock) && qty >= min) continue;
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
                    long qty = toLong(r[2]), min = toLong(r[3]);
                    if (Boolean.TRUE.equals(belowMinStock) && qty >= min) continue;
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
            long qtyIn = toLong(r[1]), qtyOut = toLong(r[2]);
            result.add(new StockMovementReportRowDTO(str(r[0]), qtyIn, qtyOut, qtyIn - qtyOut, toLong(r[3])));
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LOYALTY REPORT
    // groupBy: MEMBER | TYPE | DAY | MONTH
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LoyaltyReportRowDTO> getLoyaltyReport(
            LocalDate dateFrom, LocalDate dateTo, String groupBy, String transactionType) {
        LocalDateTime from = toFrom(dateFrom);
        LocalDateTime to   = toTo(dateTo);

        String selectLabel;
        String groupByClause;
        String extraJoin = "";

        switch (groupBy.toUpperCase()) {
            case "TYPE":
                selectLabel   = "CAST(lt.type AS string)";
                groupByClause = "lt.type";
                break;
            case "DAY":
                selectLabel   = "CONCAT(CAST(YEAR(lt.createdAt) AS string), '-', CAST(MONTH(lt.createdAt) AS string), '-', CAST(DAY(lt.createdAt) AS string))";
                groupByClause = "YEAR(lt.createdAt), MONTH(lt.createdAt), DAY(lt.createdAt)";
                break;
            case "MONTH":
                selectLabel   = "CONCAT(CAST(YEAR(lt.createdAt) AS string), '-', CAST(MONTH(lt.createdAt) AS string))";
                groupByClause = "YEAR(lt.createdAt), MONTH(lt.createdAt)";
                break;
            default: // MEMBER
                extraJoin     = "JOIN lt.loyaltyMember m ";
                selectLabel   = "CONCAT(m.cardNumber, ' - ', m.firstName, ' ', m.lastName)";
                groupByClause = "m.cardNumber, m.firstName, m.lastName";
                break;
        }

        boolean hasTypeFilter = transactionType != null && !transactionType.isBlank();
        String typeFilter = hasTypeFilter ? "AND CAST(lt.type AS string) = :transactionType " : "";

        String jpql =
                "SELECT " + selectLabel + ", " +
                "SUM(CASE WHEN lt.type = 'EARNED'   THEN lt.points ELSE 0 END), " +
                "SUM(CASE WHEN lt.type = 'REDEEMED' THEN lt.points ELSE 0 END), " +
                "SUM(CASE WHEN lt.type = 'ADJUSTED' THEN lt.points ELSE 0 END), " +
                "SUM(CASE WHEN lt.type = 'REVERSED' THEN lt.points ELSE 0 END), " +
                "COUNT(lt.id) " +
                "FROM LoyaltyTransaction lt " +
                extraJoin +
                "WHERE lt.createdAt BETWEEN :from AND :to " +
                typeFilter +
                "GROUP BY " + groupByClause + " " +
                "ORDER BY COUNT(lt.id) DESC";

        Query query = em.createQuery(jpql)
                .setParameter("from", from)
                .setParameter("to", to);
        if (hasTypeFilter) query.setParameter("transactionType", transactionType);

        List<LoyaltyReportRowDTO> result = new ArrayList<>();
        for (Object[] r : (List<Object[]>) query.getResultList()) {
            result.add(new LoyaltyReportRowDTO(
                    str(r[0]), toLong(r[1]), toLong(r[2]), toLong(r[3]), toLong(r[4]), toLong(r[5])));
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SESSION REPORT
    // groupBy: CASHIER | PAYMENT_METHOD | DAY | MONTH
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SessionReportRowDTO> getSessionReport(LocalDate dateFrom, LocalDate dateTo, String groupBy) {
        LocalDateTime from = toFrom(dateFrom);
        LocalDateTime to   = toTo(dateTo);

        List<SessionReportRowDTO> result = new ArrayList<>();

        if ("PAYMENT_METHOD".equalsIgnoreCase(groupBy)) {
            // Use SalesHeader.totalAmount as base — avoids ESPECE inflation (change tendered)
            double totalRevenue = toDouble(em.createQuery(
                    "SELECT COALESCE(SUM(sh.totalAmount), 0.0) FROM SalesHeader sh " +
                    "WHERE sh.status = 'COMPLETED' AND sh.salesDate BETWEEN :from AND :to")
                    .setParameter("from", from).setParameter("to", to).getSingleResult());

            long totalSessions = toLong(em.createQuery(
                    "SELECT COUNT(DISTINCT sh.cashierSession.id) FROM SalesHeader sh " +
                    "WHERE sh.status = 'COMPLETED' AND sh.salesDate BETWEEN :from AND :to")
                    .setParameter("from", from).setParameter("to", to).getSingleResult());

            // Non-cash types: Payment.totalAmount is accurate (no change given for these)
            double nonCashTotal = 0.0;
            for (Object[] r : (List<Object[]>) em.createQuery(
                    "SELECT pm.name, COUNT(DISTINCT p.salesHeader.cashierSession.id), " +
                    "COUNT(DISTINCT p.salesHeader.id), SUM(p.totalAmount) " +
                    "FROM Payment p JOIN p.paymentMethod pm " +
                    "WHERE p.salesHeader.status = 'COMPLETED' " +
                    "AND p.salesHeader.salesDate BETWEEN :from AND :to " +
                    "AND pm.type <> 'CLIENT_ESPECES' " +
                    "GROUP BY pm.name ORDER BY SUM(p.totalAmount) DESC")
                    .setParameter("from", from).setParameter("to", to).getResultList()) {
                double amt = toDouble(r[3]);
                nonCashTotal += amt;
                long nbTx = toLong(r[2]);
                result.add(new SessionReportRowDTO(str(r[0]), toLong(r[1]), nbTx, amt, nbTx > 0 ? amt / nbTx : 0.0));
            }

            // ESPECE = totalRevenue - nonCashTotal (correct; includes PASSENGER cash)
            List<?> pmNames = em.createQuery(
                    "SELECT pm.name FROM PaymentMethod pm WHERE pm.type = 'CLIENT_ESPECES'")
                    .setMaxResults(1).getResultList();
            String especeName = pmNames.isEmpty() ? "Espèce" : (String) pmNames.get(0);
            long especeNbTx = toLong(em.createQuery(
                    "SELECT COUNT(DISTINCT p.salesHeader.id) FROM Payment p JOIN p.paymentMethod pm " +
                    "WHERE pm.type = 'CLIENT_ESPECES' AND p.salesHeader.status = 'COMPLETED' " +
                    "AND p.salesHeader.salesDate BETWEEN :from AND :to")
                    .setParameter("from", from).setParameter("to", to).getSingleResult());
            double especeAmt = Math.max(0.0, totalRevenue - nonCashTotal);
            result.add(new SessionReportRowDTO(especeName, totalSessions, especeNbTx, especeAmt,
                    especeNbTx > 0 ? especeAmt / especeNbTx : 0.0));

            result.sort((a, b) -> Double.compare(b.getTotalAmount(), a.getTotalAmount()));
        } else {
            String selectLabel;
            String groupByClause;
            String extraJoin = "";
            String nbSessionsExpr;

            switch (groupBy.toUpperCase()) {
                case "DAY":
                    selectLabel    = "CONCAT(CAST(YEAR(sh.salesDate) AS string), '-', CAST(MONTH(sh.salesDate) AS string), '-', CAST(DAY(sh.salesDate) AS string))";
                    groupByClause  = "YEAR(sh.salesDate), MONTH(sh.salesDate), DAY(sh.salesDate)";
                    nbSessionsExpr = "COUNT(DISTINCT sh.cashierSession.id)";
                    break;
                case "MONTH":
                    selectLabel    = "CONCAT(CAST(YEAR(sh.salesDate) AS string), '-', CAST(MONTH(sh.salesDate) AS string))";
                    groupByClause  = "YEAR(sh.salesDate), MONTH(sh.salesDate)";
                    nbSessionsExpr = "COUNT(DISTINCT sh.cashierSession.id)";
                    break;
                default: // CASHIER
                    extraJoin      = "LEFT JOIN sh.cashierSession cs LEFT JOIN cs.cashier u ";
                    selectLabel    = "COALESCE(u.username, '(no cashier)')";
                    groupByClause  = "COALESCE(u.username, '(no cashier)')";
                    nbSessionsExpr = "COUNT(DISTINCT cs.id)";
                    break;
            }

            String jpql =
                    "SELECT " + selectLabel + ", " +
                    nbSessionsExpr + ", " +
                    "COUNT(sh.id), " +
                    "SUM(COALESCE(sh.totalAmount, 0)) " +
                    "FROM SalesHeader sh " +
                    extraJoin +
                    "WHERE sh.status = 'COMPLETED' " +
                    "AND sh.salesDate BETWEEN :from AND :to " +
                    "GROUP BY " + groupByClause + " " +
                    "ORDER BY SUM(COALESCE(sh.totalAmount, 0)) DESC";

            for (Object[] r : (List<Object[]>) em.createQuery(jpql)
                    .setParameter("from", from).setParameter("to", to).getResultList()) {
                long nbTx = toLong(r[2]);
                double total = toDouble(r[3]);
                result.add(new SessionReportRowDTO(str(r[0]), toLong(r[1]), nbTx, total, nbTx > 0 ? total / nbTx : 0.0));
            }
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROMOTION REPORT
    // Returns one row per promotion that was used in completed sales.
    // Merges line-level usage (sl.promotion) and header-level usage (sh.promotion).
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PromotionReportRowDTO> getPromotionReport(LocalDate dateFrom, LocalDate dateTo) {
        LocalDateTime from = toFrom(dateFrom);
        LocalDateTime to   = toTo(dateTo);

        // Map keyed by promotionCode to accumulate line + header results
        java.util.Map<String, PromotionReportRowDTO> map = new java.util.LinkedHashMap<>();

        // --- Query 1: line-level promotion usage ---
        // Excludes 100% discount free lines (discountPercentage < 100) to avoid double-counting
        // "Buy 3 Get 2 Free" as two separate entries.
        String lineJpql =
                "SELECT p.code, p.name, p.promotionType, " +
                "COUNT(DISTINCT sl.salesHeader.id), " +
                "SUM(COALESCE(sl.discountAmount, 0)), " +
                "SUM(COALESCE(sl.lineTotalIncludingVat, 0) + COALESCE(sl.discountAmount, 0)) " +
                "FROM SalesLine sl JOIN sl.promotion p " +
                "WHERE sl.salesHeader.status = 'COMPLETED' " +
                "AND sl.salesHeader.salesDate BETWEEN :from AND :to " +
                "AND (sl.discountPercentage IS NULL OR sl.discountPercentage < 100) " +
                "GROUP BY p.id, p.code, p.name, p.promotionType";

        for (Object[] r : (List<Object[]>) em.createQuery(lineJpql)
                .setParameter("from", from).setParameter("to", to).getResultList()) {
            String code = str(r[0]);
            map.put(code, new PromotionReportRowDTO(
                    code, str(r[1]), str(r[2]),
                    toLong(r[3]), toDouble(r[4]), toDouble(r[5])));
        }

        // --- Query 2: header-level (cart) promotion usage ---
        String headerJpql =
                "SELECT p.code, p.name, p.promotionType, " +
                "COUNT(sh.id), " +
                "SUM(COALESCE(sh.discountAmount, 0)), " +
                "SUM(COALESCE(sh.totalAmount, 0) + COALESCE(sh.discountAmount, 0)) " +
                "FROM SalesHeader sh JOIN sh.promotion p " +
                "WHERE sh.status = 'COMPLETED' " +
                "AND sh.salesDate BETWEEN :from AND :to " +
                "GROUP BY p.id, p.code, p.name, p.promotionType";

        for (Object[] r : (List<Object[]>) em.createQuery(headerJpql)
                .setParameter("from", from).setParameter("to", to).getResultList()) {
            String code = str(r[0]);
            if (map.containsKey(code)) {
                PromotionReportRowDTO existing = map.get(code);
                existing.setNbTickets(existing.getNbTickets() + toLong(r[3]));
                existing.setTotalDiscount(existing.getTotalDiscount() + toDouble(r[4]));
                existing.setRevenueInfluenced(existing.getRevenueInfluenced() + toDouble(r[5]));
            } else {
                map.put(code, new PromotionReportRowDTO(
                        code, str(r[1]), str(r[2]),
                        toLong(r[3]), toDouble(r[4]), toDouble(r[5])));
            }
        }

        List<PromotionReportRowDTO> result = new ArrayList<>(map.values());
        result.sort((a, b) -> Double.compare(b.getTotalDiscount(), a.getTotalDiscount()));
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private LocalDateTime toFrom(LocalDate d) {
        return d != null ? d.atStartOfDay() : LocalDateTime.of(2000, 1, 1, 0, 0);
    }

    private LocalDateTime toTo(LocalDate d) {
        return d != null ? d.atTime(LocalTime.MAX) : LocalDateTime.now();
    }

    private String str(Object o) { return o != null ? o.toString() : ""; }

    private Long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Long)    return (Long) o;
        if (o instanceof Integer) return ((Integer) o).longValue();
        if (o instanceof Number)  return ((Number) o).longValue();
        return 0L;
    }

    private Double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Double) return (Double) o;
        if (o instanceof Number) return ((Number) o).doubleValue();
        return 0.0;
    }

    private String stockStatus(long qty, long min) {
        if (qty <= 0)  return "OUT";
        if (qty < min) return "LOW";
        return "OK";
    }
}
