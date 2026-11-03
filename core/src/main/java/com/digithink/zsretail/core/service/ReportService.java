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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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

import com.digithink.zsretail.core.dto.report.ReportItemFilter;
import com.digithink.zsretail.core.dto.report.StockCardDTO;
import com.digithink.zsretail.core.dto.report.StockCardLineDTO;
import com.digithink.zsretail.core.dto.report.StockMovementReportFilter;
import com.digithink.zsretail.core.dto.report.StockMovementReportRowDTO;
import com.digithink.zsretail.core.dto.report.StockReportFilter;
import com.digithink.zsretail.core.dto.report.StockReportRowDTO;
import com.digithink.zsretail.core.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;
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
        long count;
        long low;
        long out;
        Double cost;
        Double price;
        BigDecimal vat;
        String family;
        String subFamily;

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
            row.count++;
            String status = stockStatus(qty, min);
            if ("LOW".equals(status)) {
                row.low++;
            } else if ("OUT".equals(status) || "NEGATIVE".equals(status)) {
                row.out++;
            }
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
                dto.setFamilyName(row.family);
                dto.setSubFamilyName(row.subFamily);
            }
            dto.setItemCount(row.count);
            dto.setItemsLow(row.low);
            dto.setItemsOut(row.out);
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
                item.family = r[6] == null ? null : str(r[6]);
                item.subFamily = r[7] == null ? null : str(r[7]);
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
    static String where(ReportItemFilter filter) {
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

    private static void bind(Query query, ReportItemFilter filter) {
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
    // STOCK MOVEMENTS REPORT (3.1.2 rebuild): docs/modules/reporting.md, "Stock movements report"
    // groupBy: ITEM | FAMILY | SUBFAMILY | CATEGORY | SECTION | TYPE | DAY | MONTH
    // ─────────────────────────────────────────────────────────────────────────

    /** The report of before 3.1.2: one type (its name) or every type. */
    @Transactional(readOnly = true)
    public List<StockMovementReportRowDTO> getStockMovementsReport(
            LocalDate dateFrom, LocalDate dateTo, String groupBy, String movementType) {
        StockMovementReportFilter filter = new StockMovementReportFilter();
        if (movementType != null && !movementType.isBlank()) {
            filter.getTypes().add(StockMovementType.valueOf(movementType.trim()));
        }
        return getStockMovementsReport(dateFrom, dateTo, groupBy, filter);
    }

    /**
     * 3.1.2: the movements from dateFrom to dateTo (none: since 2000 / until now), grouped, in one query.
     * <ul>
     * <li>per row: the quantities in and out, the net, the movements and the distinct items moved; an item row also its
     * code and family (its label the name; before 3.1.2 "code - name");</li>
     * <li>the filters on the item in the WHERE (shared with the stock report, {@link #where}), the types any of them;
     * COST_ADJUSTMENT never ("Ajuster le CMP" writes it with a quantity of 0: it moves no stock);</li>
     * <li>a day yyyy-MM-dd and a month yyyy-MM, in time order; the other groupings by quantity moved, the largest first;
     * a category or section labelled as in the stock report (its name, else its code, else "No Category").</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<StockMovementReportRowDTO> getStockMovementsReport(LocalDate dateFrom, LocalDate dateTo, String groupBy,
            StockMovementReportFilter filter) {
        StockMovementReportFilter f = filter == null ? new StockMovementReportFilter() : filter;
        String group = groupBy.toUpperCase();
        boolean itemJoin = f.onItem();
        String levelJoin = "";
        String keys;
        String order = "SUM(sm.quantity) DESC";
        switch (group) {
            case "FAMILY":    keys = "f.id, f.name"; itemJoin = true; break;
            case "SUBFAMILY": keys = "sf.id, sf.name, f.name"; itemJoin = true; break;
            case "CATEGORY":
                keys = "i.categoryCode, c.name";
                itemJoin = true;
                levelJoin = " LEFT JOIN ItemCategory c ON c.code = i.categoryCode";
                break;
            case "SECTION":
                keys = "i.sectionCode, s.name";
                itemJoin = true;
                levelJoin = " LEFT JOIN ItemSection s ON s.code = i.sectionCode";
                break;
            case "TYPE":      keys = "sm.movementType"; break;
            case "DAY":       keys = "YEAR(sm.createdAt), MONTH(sm.createdAt), DAY(sm.createdAt)"; order = keys; break;
            case "MONTH":     keys = "YEAR(sm.createdAt), MONTH(sm.createdAt)"; order = keys; break;
            default:          group = "ITEM"; keys = "i.id, i.itemCode, i.name, f.name"; itemJoin = true; break;
        }
        int k = keys.split(",").length; // the sums follow the keys

        String jpql = "SELECT " + keys + ", " +
                "SUM(CASE WHEN sm.direction = 'IN'  THEN sm.quantity ELSE 0 END), " +
                "SUM(CASE WHEN sm.direction = 'OUT' THEN sm.quantity ELSE 0 END), " +
                "COUNT(sm.id), COUNT(DISTINCT sm.item.id) " +
                "FROM StockMovement sm" +
                (itemJoin ? " JOIN sm.item i LEFT JOIN i.itemFamily f LEFT JOIN i.itemSubFamily sf" + levelJoin : "") +
                " WHERE sm.createdAt BETWEEN :from AND :to AND sm.movementType <> :costAdjustment" +
                (f.getTypes().isEmpty() ? "" : " AND sm.movementType IN (:types)") +
                where(f) +
                " GROUP BY " + keys + " ORDER BY " + order;
        Query query = em.createQuery(jpql)
                .setParameter("from", toFrom(dateFrom))
                .setParameter("to", toTo(dateTo))
                .setParameter("costAdjustment", StockMovementType.COST_ADJUSTMENT);
        if (!f.getTypes().isEmpty()) query.setParameter("types", f.getTypes());
        bind(query, f);

        // One row per key; a category or section without a code and one with a blank code are the same row
        Map<String, StockMovementReportRowDTO> rows = new LinkedHashMap<>();
        for (Object[] r : (List<Object[]>) query.getResultList()) {
            String label;
            String key;
            switch (group) {
                case "FAMILY":    label = r[1] == null ? "No Family" : str(r[1]); key = r[0] == null ? null : str(r[0]); break;
                case "SUBFAMILY": label = r[1] == null ? "No Subfamily" : str(r[1]); key = r[0] == null ? null : str(r[0]); break;
                case "CATEGORY":  label = label(r[1], r[0], "No Category"); key = code(r[0]); break;
                case "SECTION":   label = label(r[1], r[0], "No Section"); key = code(r[0]); break;
                case "TYPE":      label = str(r[0]); key = label; break;
                case "DAY":
                    label = String.format("%04d-%02d-%02d", ((Number) r[0]).intValue(), ((Number) r[1]).intValue(), ((Number) r[2]).intValue());
                    key = label;
                    break;
                case "MONTH":
                    label = String.format("%04d-%02d", ((Number) r[0]).intValue(), ((Number) r[1]).intValue());
                    key = label;
                    break;
                default:          label = str(r[2]); key = str(r[1]); break;
            }
            StockMovementReportRowDTO row = rows.get(String.valueOf(key));
            if (row == null) {
                row = new StockMovementReportRowDTO(label, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L);
                if ("ITEM".equals(group)) {
                    row.setItemId(((Number) r[0]).longValue());
                    row.setItemCode(str(r[1]));
                    row.setFamilyName(r[3] == null ? null : str(r[3]));
                } else {
                    row.setGroupKey(key); // null for the row of the items without one
                    if ("SUBFAMILY".equals(group) && key != null) row.setFamilyName(r[2] == null ? null : str(r[2]));
                }
                rows.put(String.valueOf(key), row);
            }
            row.setQtyIn(row.getQtyIn().add(toQuantity(r[k])));
            row.setQtyOut(row.getQtyOut().add(toQuantity(r[k + 1])));
            row.setNbMovements(row.getNbMovements() + toLong(r[k + 2]));
            row.setItemCount(row.getItemCount() + toLong(r[k + 3]));
        }
        List<StockMovementReportRowDTO> result = new ArrayList<>(rows.values());
        for (StockMovementReportRowDTO row : result) {
            row.setQtyIn(Quantities.normalize(row.getQtyIn()));
            row.setQtyOut(Quantities.normalize(row.getQtyOut()));
            row.setNetQty(Quantities.normalize(row.getQtyIn().subtract(row.getQtyOut())));
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STOCK CARD (3.1.2): one item's movements of a period, the stock after each
    // ─────────────────────────────────────────────────────────────────────────

    /** The lines of a stock card at most (a best-seller over a year): the latest ones, the card says so. */
    public static final int STOCK_CARD_MAX = 2000;

    /** An item's movements, the IN and the OUT summed, between :from and :to (COST_ADJUSTMENT never). */
    static final String CARD_SUMS =
            "SELECT m.direction, SUM(m.quantity), COUNT(m.id) FROM StockMovement m " +
            "WHERE m.item.id = :item AND m.createdAt BETWEEN :from AND :to AND m.movementType <> :costAdjustment " +
            "GROUP BY m.direction";

    /** The item's movements of the period, the latest first; the cashier of a till movement's session beside it. */
    static final String CARD_LINES =
            "SELECT m.id, m.createdAt, m.movementType, m.direction, m.quantity, m.referenceType, m.referenceId, m.notes, " +
            "m.createdBy, u.fullName, u.username FROM StockMovement m LEFT JOIN m.cashierSession cs LEFT JOIN cs.cashier u " +
            "WHERE m.item.id = :item AND m.createdAt BETWEEN :from AND :to AND m.movementType <> :costAdjustment " +
            "ORDER BY m.createdAt DESC, m.id DESC";

    /** The document numbers by reference type, read for the card's lines only (the BL's and the count's are their notes). */
    private static final Map<String, String> NUMBERS = Map.of(
            "SALE", "SELECT h.id, h.salesNumber FROM SalesHeader h WHERE h.id IN (:ids)",
            "RETURN", "SELECT h.id, h.returnNumber FROM ReturnHeader h WHERE h.id IN (:ids)",
            "PURCHASE", "SELECT h.id, h.purchaseNumber FROM PurchaseHeader h WHERE h.id IN (:ids)");

    /** The reference types whose note is the document number. */
    private static final Set<String> NUMBER_IN_NOTE = Set.of("BL", "INVENTORY");

    /**
     * 3.1.2: an item's stock card from dateFrom to dateTo (none: since 2000 / until now), null for an unknown item.
     * <ul>
     * <li>closing = the stock now minus the movements after dateTo (IN minus OUT); opening = closing minus the period's
     * movements: the rule of the stock value at a date;</li>
     * <li>the lines in time order, the stock after each one counted back from closing; at most limit lines (the latest,
     * truncated true), the period's sums and count always whole;</li>
     * <li>the reference as printed (the ticket's, the return's, the purchase's number; the BL's and the inventory
     * count's are their notes), the user (the cashier of the session for a till movement written by "System");</li>
     * <li>COST_ADJUSTMENT never, as the report. Four queries, and one per kind of document referenced (three at most).</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public StockCardDTO getStockCard(Long itemId, LocalDate dateFrom, LocalDate dateTo, int limit) {
        Object[] item = (Object[]) em.createQuery("SELECT i.itemCode, i.name, i.stockQuantity FROM Item i WHERE i.id = :item")
                .setParameter("item", itemId).getResultList().stream().findFirst().orElse(null);
        if (item == null) {
            return null;
        }
        LocalDateTime from = toFrom(dateFrom);
        LocalDateTime to = toTo(dateTo);
        BigDecimal[] after = cardSums(itemId, to.plusNanos(1), LocalDateTime.of(9999, 12, 31, 0, 0), null);
        long[] count = new long[1];
        BigDecimal[] period = cardSums(itemId, from, to, count);

        StockCardDTO card = new StockCardDTO();
        card.setItemId(itemId);
        card.setItemCode(str(item[0]));
        card.setItemName(str(item[1]));
        card.setDateFrom(from.toLocalDate());
        card.setDateTo(to.toLocalDate());
        BigDecimal closing = toQuantity(item[2]).subtract(after[0]).add(after[1]);
        card.setClosingQty(Quantities.normalize(closing));
        card.setOpeningQty(Quantities.normalize(closing.subtract(period[0]).add(period[1])));
        card.setQtyIn(Quantities.normalize(period[0]));
        card.setQtyOut(Quantities.normalize(period[1]));
        card.setMovementCount(count[0]);

        List<Object[]> rows = em.createQuery(CARD_LINES)
                .setParameter("item", itemId).setParameter("from", from).setParameter("to", to)
                .setParameter("costAdjustment", StockMovementType.COST_ADJUSTMENT)
                .setMaxResults(Math.max(1, limit)).getResultList();
        card.setTruncated(rows.size() < count[0]);

        // The latest first: the stock after each one, counted back from the closing stock
        Map<String, Set<Long>> referenced = new HashMap<>();
        BigDecimal balance = closing;
        List<StockCardLineDTO> lines = new ArrayList<>();
        for (Object[] r : rows) {
            StockCardLineDTO line = new StockCardLineDTO();
            line.setId(((Number) r[0]).longValue());
            line.setDate((LocalDateTime) r[1]);
            line.setType(str(r[2]));
            line.setDirection(str(r[3]));
            BigDecimal quantity = toQuantity(r[4]);
            line.setQuantity(quantity);
            line.setBalanceAfter(Quantities.normalize(balance));
            balance = r[3] == StockMovementDirection.OUT ? balance.add(quantity) : balance.subtract(quantity);
            String referenceType = r[5] == null ? null : str(r[5]);
            line.setReferenceType(referenceType);
            line.setReferenceId(r[6] == null ? null : ((Number) r[6]).longValue());
            String note = r[7] == null || str(r[7]).trim().isEmpty() ? null : str(r[7]);
            if (referenceType != null && NUMBER_IN_NOTE.contains(referenceType)) {
                line.setReference(note);
            } else {
                line.setNote(note);
            }
            if (referenceType != null && NUMBERS.containsKey(referenceType) && line.getReferenceId() != null) {
                referenced.computeIfAbsent(referenceType, k -> new HashSet<>()).add(line.getReferenceId());
            }
            String createdBy = r[8] == null ? null : str(r[8]);
            String cashier = r[9] != null && !str(r[9]).trim().isEmpty() ? str(r[9]) : (r[10] == null ? null : str(r[10]));
            line.setUser(cashier != null && (createdBy == null || "System".equals(createdBy)) ? cashier : createdBy);
            lines.add(line);
        }
        referenced.forEach((type, ids) -> {
            Map<Long, String> numbers = new HashMap<>();
            for (Object[] r : (List<Object[]>) em.createQuery(NUMBERS.get(type)).setParameter("ids", ids).getResultList()) {
                if (r[1] != null) numbers.put(((Number) r[0]).longValue(), str(r[1]));
            }
            lines.stream().filter(l -> type.equals(l.getReferenceType()))
                    .forEach(l -> l.setReference(numbers.get(l.getReferenceId())));
        });
        Collections.reverse(lines);
        card.setLines(lines);
        return card;
    }

    /** IN and OUT of an item's movements between from and to; count[0] the movements when asked. */
    private BigDecimal[] cardSums(Long itemId, LocalDateTime from, LocalDateTime to, long[] count) {
        BigDecimal[] sums = { BigDecimal.ZERO, BigDecimal.ZERO };
        for (Object[] r : (List<Object[]>) em.createQuery(CARD_SUMS).setParameter("item", itemId)
                .setParameter("from", from).setParameter("to", to)
                .setParameter("costAdjustment", StockMovementType.COST_ADJUSTMENT).getResultList()) {
            sums[r[0] == StockMovementDirection.OUT ? 1 : 0] = toQuantity(r[1]);
            if (count != null) count[0] += toLong(r[2]);
        }
        return sums;
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
