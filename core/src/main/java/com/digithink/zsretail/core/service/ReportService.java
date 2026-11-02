package com.digithink.zsretail.core.service;

import static com.digithink.zsretail.core.service.ReportValues.str;
import static com.digithink.zsretail.core.service.ReportValues.toFrom;
import static com.digithink.zsretail.core.service.ReportValues.toLong;
import static com.digithink.zsretail.core.service.ReportValues.toQuantity;
import static com.digithink.zsretail.core.service.ReportValues.toTo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.dto.report.StockMovementReportRowDTO;
import com.digithink.zsretail.core.dto.report.StockReportFilter;
import com.digithink.zsretail.core.dto.report.StockReportRowDTO;
import com.digithink.zsretail.core.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.core.repository.PosItemSearchRepository;
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
    // STOCK REPORT, now or at the end of a past day (3.1.2): docs/modules/reporting.md, "Stock report"
    // groupBy: ITEM | FAMILY | SUBFAMILY | CATEGORY | SECTION
    // ─────────────────────────────────────────────────────────────────────────

    /** The stock now, the filter of before 3.1.2 (below the minimum: the statuses LOW, OUT, NEGATIVE). */
    @Transactional(readOnly = true)
    public List<StockReportRowDTO> getStockReport(String groupBy, Boolean belowMinStock) {
        return getStockReport(groupBy, StockReportFilter.belowMin(belowMinStock), null);
    }

    /** The stock at a date, the filter of before 3.1.2. */
    @Transactional(readOnly = true)
    public List<StockReportRowDTO> getStockReport(String groupBy, Boolean belowMinStock, LocalDate date) {
        return getStockReport(groupBy, StockReportFilter.belowMin(belowMinStock), date);
    }

    /**
     * The active items with what the stock and its values need, the category and section names by their codes (unique);
     * the filters ({@link #where}) and the grouping's order appended.
     */
    static final String STOCK_ITEMS =
            "SELECT i.id, i.name, i.itemCode, i.stockQuantity, i.minStockLevel, i.costPrice, f.name, sf.name, " +
            "c.name, i.categoryCode, s.name, i.sectionCode, i.unitPrice, i.defaultVAT " +
            "FROM Item i LEFT JOIN i.itemFamily f LEFT JOIN i.itemSubFamily sf " +
            "LEFT JOIN ItemCategory c ON c.code = i.categoryCode LEFT JOIN ItemSection s ON s.code = i.sectionCode " +
            "WHERE i.active = true";

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
     * 3.1.2: the stock report, one path for now and a past day. A date before today: the stock and its value at the end
     * of that day (ReportAPI refuses a date after today, and a past date where the installation keeps no stock of its
     * own); null or today: the stock now.
     * <ul>
     * <li>qty = the stock now; at a past D minus the movements after D (IN minus OUT, one grouped query);</li>
     * <li>CMP = the CMP now; at a past D the new cost of the item's last cost change up to D, else the old cost of its
     * first change after D, else its CMP now (counted in itemsAtCurrentCmp);</li>
     * <li>value = qty x CMP (HT), with VAT x (1 + defaultVAT / 100), a null VAT 0 as at the till; without a CMP and with a
     * stock other than 0: no value (null, itemsWithoutCost), a stock of 0: 0. The selling value the same with the
     * item's unitPrice now (HT; itemsWithoutPrice);</li>
     * <li>the filters on the item, in the items query; the status (any of the filter's) on the item's own qty and CMP at
     * the date, before the grouping.</li>
     * </ul>
     * One query now, four at a past date, whatever the number of items (the items, the movements after D, the two ends of
     * the cost history), grouped here in the order of the items query; every active item the filter keeps is listed, a
     * stock of 0 included.
     */
    @Transactional(readOnly = true)
    public List<StockReportRowDTO> getStockReport(String groupBy, StockReportFilter filter, LocalDate date) {
        LocalDateTime after = date == null || !date.isBefore(LocalDate.now()) ? null : date.plusDays(1).atStartOfDay();
        return stock(groupBy.toUpperCase(), filter == null ? new StockReportFilter() : filter, after);
    }

    /** One row of the report being summed: an item, or a group and its items. Item row only: its CMP, price and VAT. */
    private static final class StockAt {
        final String label;
        final String code;
        BigDecimal qty = BigDecimal.ZERO;
        long min;
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal valueTtc = BigDecimal.ZERO;
        BigDecimal selling = BigDecimal.ZERO;
        BigDecimal sellingTtc = BigDecimal.ZERO;
        long withoutCost;
        long withoutPrice;
        long atCurrentCmp;
        Double cost;
        Double price;
        BigDecimal vat;

        StockAt(String label, String code) {
            this.label = label;
            this.code = code;
        }
    }

    /** after: the end of the past day asked, null for the stock now (no movements nor history read). */
    private List<StockReportRowDTO> stock(String groupBy, StockReportFilter filter, LocalDateTime after) {
        Query itemsQuery = em.createQuery(STOCK_ITEMS + where(filter) + " ORDER BY " + order(groupBy) + " ASC");
        bind(itemsQuery, filter);
        List<Object[]> items = itemsQuery.getResultList();
        Map<Long, BigDecimal> movedAfter = new HashMap<>();
        Map<Long, Double> costBefore = new HashMap<>();
        Map<Long, Double> costFrom = new HashMap<>();
        if (after != null) {
            for (Object[] r : (List<Object[]>) em.createQuery(MOVEMENTS_AFTER).setParameter("after", after).getResultList()) {
                BigDecimal quantity = toQuantity(r[2]);
                movedAfter.merge(((Number) r[0]).longValue(),
                        r[1] == StockMovementDirection.OUT ? quantity.negate() : quantity, BigDecimal::add);
            }
            costBefore = costs(COSTS_BEFORE, after);
            costFrom = costs(COSTS_FROM, after);
        }

        Map<Object, StockAt> rows = new LinkedHashMap<>();
        for (Object[] r : items) {
            Long id = ((Number) r[0]).longValue();
            BigDecimal qty = toQuantity(r[3]).subtract(movedAfter.getOrDefault(id, BigDecimal.ZERO));
            long min = toLong(r[4]);
            boolean atCurrent = after != null && !costBefore.containsKey(id) && !costFrom.containsKey(id);
            Double cmp = costBefore.containsKey(id) ? costBefore.get(id)
                    : costFrom.containsKey(id) ? costFrom.get(id) : (r[5] == null ? null : ((Number) r[5]).doubleValue());
            if (!kept(filter, qty, min, cmp)) {
                continue;
            }
            Double price = r[12] == null ? null : ((Number) r[12]).doubleValue();
            BigDecimal vat = withVat(r[13]);
            StockAt row = rows.computeIfAbsent(groupKey(groupBy, r, id), k -> newRow(groupBy, r, cmp, price, vat));
            row.qty = row.qty.add(qty);
            row.min += min;
            if (qty.signum() == 0) {
                continue; // a stock of 0 is worth 0, with or without a cost or a price
            }
            if (cmp == null) {
                row.withoutCost++;
            } else {
                BigDecimal value = qty.multiply(BigDecimal.valueOf(cmp));
                row.value = row.value.add(value);
                row.valueTtc = row.valueTtc.add(value.multiply(vat));
                if (atCurrent) {
                    row.atCurrentCmp++;
                }
            }
            if (price == null) {
                row.withoutPrice++;
            } else {
                BigDecimal selling = qty.multiply(BigDecimal.valueOf(price));
                row.selling = row.selling.add(selling);
                row.sellingTtc = row.sellingTtc.add(selling.multiply(vat));
            }
        }

        List<StockReportRowDTO> result = new ArrayList<>();
        for (StockAt row : rows.values()) {
            BigDecimal qty = Quantities.normalize(row.qty);
            boolean itemRow = row.code != null;
            boolean noValue = itemRow && row.withoutCost > 0;
            boolean noSelling = itemRow && row.withoutPrice > 0;
            StockReportRowDTO dto = new StockReportRowDTO(row.label, row.code, qty, row.min,
                    noValue ? null : rounded(row.value), stockStatus(qty, row.min));
            dto.setStockValueTtc(noValue ? null : rounded(row.valueTtc));
            dto.setSellingValue(noSelling ? null : rounded(row.selling));
            dto.setSellingValueTtc(noSelling ? null : rounded(row.sellingTtc));
            if (itemRow) {
                dto.setCostPrice(row.cost);
                dto.setCostPriceTtc(row.cost == null ? null : rounded(BigDecimal.valueOf(row.cost).multiply(row.vat)));
                dto.setUnitPrice(row.price);
                dto.setUnitPriceTtc(row.price == null ? null : rounded(BigDecimal.valueOf(row.price).multiply(row.vat)));
            }
            dto.setItemsWithoutCost(row.withoutCost > 0 ? row.withoutCost : null);
            dto.setItemsWithoutPrice(row.withoutPrice > 0 ? row.withoutPrice : null);
            dto.setItemsAtCurrentCmp(row.atCurrentCmp > 0 ? row.atCurrentCmp : null);
            result.add(dto);
        }
        return result;
    }

    /** The items query's order, the grouping's name (a category or section without a name: its code). */
    private static String order(String groupBy) {
        switch (groupBy) {
            case "FAMILY":    return "f.name";
            case "SUBFAMILY": return "sf.name";
            case "CATEGORY":  return "COALESCE(c.name, i.categoryCode)";
            case "SECTION":   return "COALESCE(s.name, i.sectionCode)";
            default:          return "i.name"; // ITEM
        }
    }

    /** The row of an item: its family / sub-family name, its category / section code, else itself. */
    private static Object groupKey(String groupBy, Object[] r, Long id) {
        switch (groupBy) {
            case "FAMILY":    return r[6];
            case "SUBFAMILY": return r[7];
            case "CATEGORY":  return code(r[9]);
            case "SECTION":   return code(r[11]);
            default:          return id;
        }
    }

    private static StockAt newRow(String groupBy, Object[] r, Double cmp, Double price, BigDecimal vat) {
        switch (groupBy) {
            case "FAMILY":    return new StockAt(r[6] == null ? "No Family" : str(r[6]), null);
            case "SUBFAMILY": return new StockAt(r[7] == null ? "No Subfamily" : str(r[7]), null);
            case "CATEGORY":  return new StockAt(label(r[8], r[9], "No Category"), null);
            case "SECTION":   return new StockAt(label(r[10], r[11], "No Section"), null);
            default: {
                StockAt item = new StockAt(str(r[1]), str(r[2]));
                item.cost = cmp;
                item.price = price;
                item.vat = vat;
                return item;
            }
        }
    }

    /** A category or section: its name, else its code (no row of that code), else none. */
    private static String label(Object name, Object code, String none) {
        String key = code(code);
        return key == null ? none : name != null ? str(name) : key;
    }

    /** A blank code is none. */
    private static String code(Object code) {
        return code == null || code.toString().trim().isEmpty() ? null : code.toString();
    }

    /** The filters on the item, in the items query (the status, which needs the qty at the date, in {@link #kept}). */
    static String where(StockReportFilter filter) {
        StringBuilder where = new StringBuilder();
        if (filter.getSearch() != null) {
            where.append(" AND (LOWER(i.itemCode) LIKE :search ESCAPE '\\' OR LOWER(i.name) LIKE :search ESCAPE '\\')");
        }
        if (filter.getFamilyId() != null) where.append(" AND f.id = :familyId");
        if (filter.getSubFamilyId() != null) where.append(" AND sf.id = :subFamilyId");
        if (filter.getCategoryCode() != null) where.append(" AND i.categoryCode = :categoryCode");
        if (filter.getSectionCode() != null) where.append(" AND i.sectionCode = :sectionCode");
        return where.toString();
    }

    private static void bind(Query query, StockReportFilter filter) {
        if (filter.getSearch() != null) {
            query.setParameter("search",
                    "%" + PosItemSearchRepository.escapeLike(filter.getSearch().toLowerCase(Locale.ROOT)) + "%");
        }
        if (filter.getFamilyId() != null) query.setParameter("familyId", filter.getFamilyId());
        if (filter.getSubFamilyId() != null) query.setParameter("subFamilyId", filter.getSubFamilyId());
        if (filter.getCategoryCode() != null) query.setParameter("categoryCode", filter.getCategoryCode());
        if (filter.getSectionCode() != null) query.setParameter("sectionCode", filter.getSectionCode());
    }

    /** The item's own status at the date among the filter's (none: every item); WITHOUT_COST: no CMP, a stock other than 0. */
    private boolean kept(StockReportFilter filter, BigDecimal qty, long min, Double cmp) {
        Set<String> statuses = filter.getStatuses();
        if (statuses == null || statuses.isEmpty()) {
            return true;
        }
        return statuses.contains(stockStatus(qty, min))
                || statuses.contains(StockReportFilter.WITHOUT_COST) && cmp == null && qty.signum() != 0;
    }

    /** 1 + defaultVAT / 100; no VAT: 0, as the till sells the item. */
    private static BigDecimal withVat(Object vat) {
        return vat == null ? BigDecimal.ONE : BigDecimal.ONE.add(BigDecimal.valueOf(((Number) vat).longValue()).movePointLeft(2));
    }

    /** A value of the report: computed per item, the row rounded at the end, 3 decimals. */
    private static Double rounded(BigDecimal value) {
        return value.setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    /** One end of the cost history, item id to cost (a null old cost kept: the item had none then). */
    private Map<Long, Double> costs(String jpql, LocalDateTime after) {
        Map<Long, Double> costs = new HashMap<>();
        for (Object[] r : (List<Object[]>) em.createQuery(jpql).setParameter("after", after).getResultList()) {
            costs.put(((Number) r[0]).longValue(), r[1] == null ? null : ((Number) r[1]).doubleValue());
        }
        return costs;
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
