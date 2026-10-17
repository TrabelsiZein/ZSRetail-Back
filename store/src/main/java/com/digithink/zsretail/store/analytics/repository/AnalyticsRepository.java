package com.digithink.zsretail.store.analytics.repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;

import org.springframework.stereotype.Repository;

@Repository
public class AnalyticsRepository {

	@PersistenceContext
	private EntityManager entityManager;

	private static String formatDateParam(LocalDate date) {
		return date.format(DateTimeFormatter.ISO_LOCAL_DATE); // yyyy-MM-dd
	}

	/**
	 * @return Object[]: [revenue(BigDecimal), transactionCount(Long)]
	 */
	@SuppressWarnings("unchecked")
	public Object[] getSalesSummary(LocalDate from, LocalDate to) {
		String sql = "SELECT SUM(sh.total_amount) AS revenue, COUNT(sh.id) AS transaction_count " +
				"FROM sales_header sh " +
				"WHERE sh.status = 'COMPLETED' " +
				"AND CAST(sh.sales_date AS DATE) BETWEEN :from AND :to";

		List<Object[]> rows = entityManager
				.createNativeQuery(sql)
				.setParameter("from", formatDateParam(from))
				.setParameter("to", formatDateParam(to))
				.getResultList();

		return rows.isEmpty() ? new Object[] { null, 0L } : rows.get(0);
	}

	/**
	 * @return Object[]: [totalReturns(BigDecimal)]
	 */
	public Object[] getReturnsSummary(LocalDate from, LocalDate to) {
		String sql = "SELECT SUM(rh.total_return_amount) AS total_returns " +
				"FROM return_header rh " +
				"WHERE rh.status = 'COMPLETED' " +
				"AND CAST(rh.return_date AS DATE) BETWEEN :from AND :to";

		List<?> rows = entityManager
				.createNativeQuery(sql)
				.setParameter("from", formatDateParam(from))
				.setParameter("to", formatDateParam(to))
				.getResultList();

		if (rows.isEmpty()) {
			return new Object[] { null };
		}

		Object first = rows.get(0);
		// Single-column native aggregate queries return scalar values (not Object[]).
		return new Object[] { first };
	}

	/**
	 * @return List<Object[]>: [date, revenue(BigDecimal), transactionCount(Long)] per day
	 */
	@SuppressWarnings("unchecked")
	public List<Object[]> getSalesTrend(LocalDate from, LocalDate to) {
		String sql = "SELECT CAST(sh.sales_date AS DATE) AS day, " +
				"       SUM(sh.total_amount) AS revenue, " +
				"       COUNT(sh.id) AS transaction_count " +
				"FROM sales_header sh " +
				"WHERE sh.status = 'COMPLETED' " +
				"  AND CAST(sh.sales_date AS DATE) BETWEEN :from AND :to " +
				"GROUP BY CAST(sh.sales_date AS DATE) " +
				"ORDER BY day ASC";

		return entityManager
				.createNativeQuery(sql)
				.setParameter("from", formatDateParam(from))
				.setParameter("to", formatDateParam(to))
				.getResultList();
	}

	/**
	 * @return List<Object[]>: [itemCode, itemName, familyName, quantitySold(Long), revenue(BigDecimal)]; 3.1.0 (test log
	 *         I-49): an item without a family is counted too, its family "—" (the join was an inner one: a catalogue
	 *         without families, ZEN, had no top product at all)
	 */
	@SuppressWarnings("unchecked")
	public List<Object[]> getTopProducts(LocalDate from, LocalDate to, int limit) {
		String sql = "SELECT TOP (:limit) " +
				"       i.item_code AS item_code, " +
				"       i.name AS item_name, " +
				"       COALESCE(f.name, N'—') AS family_name, " +
				"       SUM(sl.quantity) AS quantity_sold, " +
				"       SUM(sl.line_total_including_vat) AS revenue " +
				"FROM sales_line sl " +
				"JOIN item i ON i.id = sl.item_id " +
				"LEFT JOIN item_family f ON f.id = i.item_family_id " +
				"JOIN sales_header sh ON sh.id = sl.sales_header_id " +
				"WHERE sh.status = 'COMPLETED' " +
				"  AND CAST(sh.sales_date AS DATE) BETWEEN :from AND :to " +
				"GROUP BY i.item_code, i.name, f.name " +
				"ORDER BY revenue DESC";

		return entityManager
				.createNativeQuery(sql)
				.setParameter("from", formatDateParam(from))
				.setParameter("to", formatDateParam(to))
				.setParameter("limit", limit)
				.getResultList();
	}

	/**
	 * @return List<Object[]>: [methodCode, methodName, totalAmount(BigDecimal)]; 3.1.0 (test log I-50): the cash is net
	 *         of the change given back, as the ticket Z (SessionReportService.paymentsByMethod): the change of each ticket
	 *         that had a cash payment, once per ticket, taken off the cash method (it counted the cash tendered)
	 */
	@SuppressWarnings("unchecked")
	public List<Object[]> getPaymentBreakdown(LocalDate from, LocalDate to) {
		String sql = "SELECT pm.code AS method_code, " +
				"       pm.name AS method_name, " +
				"       SUM(p.total_amount) - CASE WHEN pm.type = 'CLIENT_ESPECES' THEN " +
				"           (SELECT COALESCE(SUM(c.change_amount), 0) FROM sales_header c " +
				"            WHERE c.status = 'COMPLETED' AND c.change_amount > 0 " +
				"              AND CAST(c.sales_date AS DATE) BETWEEN :from AND :to " +
				"              AND EXISTS (SELECT 1 FROM payment cp JOIN payment_method cpm ON cpm.id = cp.payment_method_id " +
				"                          WHERE cp.sales_header_id = c.id AND cpm.type = 'CLIENT_ESPECES')) " +
				"         ELSE 0 END AS total_amount " +
				"FROM payment p " +
				"JOIN payment_method pm ON pm.id = p.payment_method_id " +
				"JOIN sales_header sh ON sh.id = p.sales_header_id " +
				"WHERE sh.status = 'COMPLETED' " +
				"  AND CAST(sh.sales_date AS DATE) BETWEEN :from AND :to " +
				"GROUP BY pm.code, pm.name, pm.type " +
				"ORDER BY total_amount DESC";

		return entityManager
				.createNativeQuery(sql)
				.setParameter("from", formatDateParam(from))
				.setParameter("to", formatDateParam(to))
				.getResultList();
	}
}

