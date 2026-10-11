package com.digithink.zsretail.service;

import static com.digithink.zsretail.core.service.ReportValues.str;
import static com.digithink.zsretail.core.service.ReportValues.toDouble;
import static com.digithink.zsretail.core.service.ReportValues.toFrom;
import static com.digithink.zsretail.core.service.ReportValues.toLong;
import static com.digithink.zsretail.core.service.ReportValues.toQuantity;
import static com.digithink.zsretail.core.service.ReportValues.toTo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.dto.report.LoyaltyReportRowDTO;
import com.digithink.zsretail.core.dto.report.PromotionReportRowDTO;
import com.digithink.zsretail.core.dto.report.PurchaseReportRowDTO;
import com.digithink.zsretail.core.dto.report.SalesReportRowDTO;
import com.digithink.zsretail.core.dto.report.SessionReportRowDTO;

import lombok.extern.log4j.Log4j2;

/**
 * The store's reports: sales, purchases, loyalty, sessions, promotions (GROUP BY aggregation queries, JPQL via
 * EntityManager, as ReportService). Code cleanup step 5: were in ReportService; a head office shows only the stock
 * reports.
 */
@Service
@Log4j2
@SuppressWarnings("unchecked")
public class SalesReportService {

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
                    str(r[0]), toLong(r[1]), toQuantity(r[2]),
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
}
