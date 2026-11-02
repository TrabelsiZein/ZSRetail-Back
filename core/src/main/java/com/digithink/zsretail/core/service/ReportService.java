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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.dto.report.StockMovementReportRowDTO;
import com.digithink.zsretail.core.dto.report.StockReportRowDTO;
import com.digithink.zsretail.core.model.enumeration.StockMovementDirection;
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
        markWithoutCost(groupBy.toUpperCase(), result);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STOCK VALUE AT A DATE (3.1.2): docs/modules/reporting.md, "Stock report"
    // ─────────────────────────────────────────────────────────────────────────

    /** The active items with what the past stock and value need, in the order of the report's grouping. */
    static final String STOCK_ITEMS =
            "SELECT i.id, i.name, i.itemCode, i.stockQuantity, i.minStockLevel, i.costPrice, f.name, sf.name " +
            "FROM Item i LEFT JOIN i.itemFamily f LEFT JOIN i.itemSubFamily sf WHERE i.active = true";

    /** The movements after the day, summed per item and direction (index ix_stock_movement_created). */
    static final String MOVEMENTS_AFTER =
            "SELECT m.item.id, m.direction, SUM(m.quantity) FROM StockMovement m " +
            "WHERE m.createdAt >= :after GROUP BY m.item.id, m.direction";

    /** Per item, the new cost of its last change before the end of the day (the ids follow the time of the changes). */
    static final String COSTS_BEFORE =
            "SELECT h.itemId, h.newCost FROM ItemCostHistory h WHERE h.id IN " +
            "(SELECT MAX(x.id) FROM ItemCostHistory x WHERE x.changedAt < :after GROUP BY x.itemId)";

    /** Per item, the old cost of its first change after the day (its cost on that day when nothing changed it before). */
    static final String COSTS_FROM =
            "SELECT h.itemId, h.oldCost FROM ItemCostHistory h WHERE h.id IN " +
            "(SELECT MIN(x.id) FROM ItemCostHistory x WHERE x.changedAt >= :after GROUP BY x.itemId)";

    /**
     * 3.1.2: the stock report at the end of a day. A date before today: the stock and its value at the end of that day
     * (ReportAPI refuses a date after today, and a past date where the installation keeps no stock of its own); null or
     * today: the stock now, {@link #getStockReport(String, Boolean)}. Same grouping, columns and order.
     * <ul>
     * <li>qty(D) = the stock now minus the movements after D (IN minus OUT, one grouped query);</li>
     * <li>CMP(D) = the new cost of the item's last cost change up to D, else the old cost of its first change after D,
     * else its CMP now (counted in itemsAtCurrentCmp);</li>
     * <li>value = qty(D) x CMP(D); without a CMP and with a stock other than 0: no value (null, itemsWithoutCost), a stock
     * of 0: 0.</li>
     * </ul>
     * Four queries whatever the number of items (the items, the movements after D, the two ends of the cost history),
     * grouped here; every active item is listed, a stock of 0 included, as today.
     */
    @Transactional(readOnly = true)
    public List<StockReportRowDTO> getStockReport(String groupBy, Boolean belowMinStock, LocalDate date) {
        if (date == null || !date.isBefore(LocalDate.now())) {
            return getStockReport(groupBy, belowMinStock);
        }
        return stockAt(groupBy.toUpperCase(), belowMinStock, date.plusDays(1).atStartOfDay());
    }

    /** One row of the report being summed: an item, or a family / sub-family and its items. */
    private static final class StockAt {
        final String label;
        final String code;
        BigDecimal qty = BigDecimal.ZERO;
        long min;
        BigDecimal value = BigDecimal.ZERO;
        long withoutCost;
        long atCurrentCmp;

        StockAt(String label, String code) {
            this.label = label;
            this.code = code;
        }
    }

    private List<StockReportRowDTO> stockAt(String groupBy, Boolean belowMinStock, LocalDateTime after) {
        String order = "FAMILY".equals(groupBy) ? "f.name" : "SUBFAMILY".equals(groupBy) ? "sf.name" : "i.name";
        List<Object[]> items = em.createQuery(STOCK_ITEMS + " ORDER BY " + order + " ASC").getResultList();
        Map<Long, BigDecimal> movedAfter = new HashMap<>();
        for (Object[] r : (List<Object[]>) em.createQuery(MOVEMENTS_AFTER).setParameter("after", after).getResultList()) {
            BigDecimal quantity = toQuantity(r[2]);
            movedAfter.merge(((Number) r[0]).longValue(),
                    r[1] == StockMovementDirection.OUT ? quantity.negate() : quantity, BigDecimal::add);
        }
        Map<Long, Double> costBefore = costs(COSTS_BEFORE, after);
        Map<Long, Double> costFrom = costs(COSTS_FROM, after);

        Map<Object, StockAt> rows = new LinkedHashMap<>();
        for (Object[] r : items) {
            Long id = ((Number) r[0]).longValue();
            StockAt row;
            if ("FAMILY".equals(groupBy)) {
                row = rows.computeIfAbsent(r[6], k -> new StockAt(r[6] == null ? "No Family" : str(r[6]), null));
            } else if ("SUBFAMILY".equals(groupBy)) {
                row = rows.computeIfAbsent(r[7], k -> new StockAt(r[7] == null ? "No Subfamily" : str(r[7]), null));
            } else {
                row = rows.computeIfAbsent(id, k -> new StockAt(str(r[1]), str(r[2])));
            }
            BigDecimal qty = toQuantity(r[3]).subtract(movedAfter.getOrDefault(id, BigDecimal.ZERO));
            boolean atCurrent = !costBefore.containsKey(id) && !costFrom.containsKey(id);
            Double cmp = costBefore.containsKey(id) ? costBefore.get(id)
                    : costFrom.containsKey(id) ? costFrom.get(id) : (r[5] == null ? null : ((Number) r[5]).doubleValue());
            row.qty = row.qty.add(qty);
            row.min += toLong(r[4]);
            if (qty.signum() == 0) {
                continue; // a stock of 0 is worth 0, with or without a cost
            }
            if (cmp == null) {
                row.withoutCost++;
            } else {
                row.value = row.value.add(qty.multiply(BigDecimal.valueOf(cmp)));
                if (atCurrent) {
                    row.atCurrentCmp++;
                }
            }
        }

        List<StockReportRowDTO> result = new ArrayList<>();
        for (StockAt row : rows.values()) {
            BigDecimal qty = Quantities.normalize(row.qty);
            if (Boolean.TRUE.equals(belowMinStock) && qty.compareTo(BigDecimal.valueOf(row.min)) >= 0) continue;
            boolean itemWithoutCost = row.code != null && row.withoutCost > 0;
            StockReportRowDTO dto = new StockReportRowDTO(row.label, row.code, qty, row.min,
                    itemWithoutCost ? null : row.value.doubleValue(), stockStatus(qty, row.min));
            dto.setItemsWithoutCost(row.withoutCost > 0 ? row.withoutCost : null);
            dto.setItemsAtCurrentCmp(row.atCurrentCmp > 0 ? row.atCurrentCmp : null);
            result.add(dto);
        }
        return result;
    }

    /** One end of the cost history, item id to cost (a null old cost kept: the item had none then). */
    private Map<Long, Double> costs(String jpql, LocalDateTime after) {
        Map<Long, Double> costs = new HashMap<>();
        for (Object[] r : (List<Object[]>) em.createQuery(jpql).setParameter("after", after).getResultList()) {
            costs.put(((Number) r[0]).longValue(), r[1] == null ? null : ((Number) r[1]).doubleValue());
        }
        return costs;
    }

    /**
     * 3.1.2, today: the items without cost (no CMP, a stock other than 0), by one more query (the report's own are
     * unchanged): an item row has no value (null, never 0) and itemsWithoutCost 1; a family or sub-family row the count
     * of its items, its value the sum of the others as before.
     */
    private void markWithoutCost(String groupBy, List<StockReportRowDTO> rows) {
        String where = " WHERE i.active = true AND i.costPrice IS NULL AND COALESCE(i.stockQuantity, 0) <> 0";
        if ("FAMILY".equals(groupBy) || "SUBFAMILY".equals(groupBy)) {
            String jpql = "FAMILY".equals(groupBy)
                    ? "SELECT COALESCE(f.name, 'No Family'), COUNT(i) FROM Item i LEFT JOIN i.itemFamily f" + where + " GROUP BY f.name"
                    : "SELECT COALESCE(sf.name, 'No Subfamily'), COUNT(i) FROM Item i LEFT JOIN i.itemSubFamily sf" + where + " GROUP BY sf.name";
            Map<String, Long> counts = new HashMap<>();
            for (Object r : em.createQuery(jpql).getResultList()) {
                if (r instanceof Object[] && ((Object[]) r).length == 2 && ((Object[]) r)[1] instanceof Number) {
                    counts.merge(str(((Object[]) r)[0]), ((Number) ((Object[]) r)[1]).longValue(), Long::sum);
                }
            }
            for (StockReportRowDTO row : rows) {
                Long count = counts.get(row.getGroupLabel());
                if (count != null && count > 0) row.setItemsWithoutCost(count);
            }
            return;
        }
        Set<String> codes = new HashSet<>();
        for (Object code : em.createQuery("SELECT i.itemCode FROM Item i" + where).getResultList()) {
            if (code instanceof String) codes.add((String) code);
        }
        for (StockReportRowDTO row : rows) {
            if (codes.contains(row.getItemCode())) {
                row.setStockValue(null);
                row.setItemsWithoutCost(1L);
            }
        }
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
        if (qty.signum() < 0)   return "NEGATIVE"; // 3.1.0 (test log I-52): a data problem, not an empty shelf
        if (qty.signum() == 0)  return "OUT"; // 2.2.1: 0.2 is in stock
        if (qty.compareTo(BigDecimal.valueOf(min)) < 0) return "LOW";
        return "OK";
    }
}
