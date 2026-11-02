package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import com.digithink.zsretail.core.model.enumeration.ItemType;

/**
 * 3.1.2, the Journal's gains (docs/modules/cash-sessions.md, "Journal"): the cost of the goods sold and the gross margin
 * of a ticket, a return and a day, from the unit costs frozen on the lines (SalesLine.unitCost, ReturnLine.unitCost).
 * One rule for the day list (StoreDaysService, the lines read by one projection per kind) and the day journal
 * (DayJournalService, the lines it has read): the same lines give the same numbers.
 * <ul>
 * <li>A line: the tax stamp is left out of everything; an item that carries no cost by nature (a service, a discount
 * item, ItemType.carriesNoCost) is costed at 0 when its cost is null; any other line with a null cost is "without cost"
 * (never 0).</li>
 * <li>A ticket: its HT as the Journal's, what was paid: each costed line's TTC before VAT times the ticket's factor
 * (total paid - stamp) / goods TTC (SessionReportService.addTicketTtcByRate), so the ticket discount, the cart
 * promotions and the loyalty points are in it. Cost = sum of quantity x unit cost; margin = that HT - cost; both over the
 * costed lines only.</li>
 * <li>A return: its HT as the Journal's (the lines' HT x refund / lines' TTC), over its costed lines; cost and margin count
 * negative in the day.</li>
 * <li>No costed line (a document or a day, e.g. before 3.1.2): every field null. Margin % = margin / HT, to one decimal,
 * only when no line is without cost and the HT is not 0.</li>
 * </ul>
 * Amounts to the millime, half up, as the Journal's.
 */
public final class JournalCosts {

	private static final int SCALE = 10;

	private JournalCosts() {
	}

	/** One line of a document, as read: an entity or a projection row. */
	public static final class Line {
		final boolean stamp;
		final boolean noCostType;
		final BigDecimal ttc;
		final BigDecimal ht;
		final Integer vatPercent;
		final BigDecimal quantity;
		final BigDecimal unitCost;

		public Line(String itemCode, ItemType type, Double ttc, Double ht, Integer vatPercent, BigDecimal quantity,
				BigDecimal unitCost) {
			this.stamp = "TAX_STAMP".equals(itemCode);
			this.noCostType = ItemType.noCost(type);
			this.ttc = big(ttc);
			this.ht = big(ht);
			this.vatPercent = vatPercent;
			this.quantity = quantity == null ? BigDecimal.ZERO : quantity;
			this.unitCost = unitCost;
		}

		/** The cost the line counts with: its own, 0 for a type without cost, null when unknown. */
		BigDecimal knownCost() {
			if (unitCost != null) {
				return unitCost;
			}
			return noCostType ? BigDecimal.ZERO : null;
		}
	}

	/** A document's or a day's numbers; every field null when nothing is costed. */
	public static final class Result {
		/** Cost of the goods sold (negative for a return). */
		public BigDecimal cost;
		/** Gross margin before VAT (negative for a return). */
		public BigDecimal margin;
		/** The HT of the costed lines (negative for a return): the base of the margin %. */
		public BigDecimal ht;
		/** Lines without a cost. */
		public Long linesWithoutCost;
		/** Margin / HT x 100, one decimal; null with a line without cost or an HT of 0. */
		public Double marginPercent;

		boolean costed() {
			return cost != null;
		}

		public Double costValue() {
			return money(cost);
		}

		public Double marginValue() {
			return money(margin);
		}
	}

	/** A ticket: its lines and its total paid (null: goods + stamp, as the Journal). */
	public static Result ticket(List<Line> lines, Double totalAmount) {
		BigDecimal goods = BigDecimal.ZERO;
		BigDecimal stamp = BigDecimal.ZERO;
		for (Line line : lines) {
			if (line.stamp) {
				stamp = stamp.add(line.ttc);
			} else {
				goods = goods.add(line.ttc);
			}
		}
		if (goods.signum() <= 0) {
			return new Result();
		}
		BigDecimal total = totalAmount != null ? big(totalAmount) : goods.add(stamp);
		BigDecimal factor = total.subtract(stamp).divide(goods, SCALE, RoundingMode.HALF_UP);
		return document(lines, factor, true);
	}

	/** A return: its lines and the amount given back (positive); the result is negative, as the return in the day. */
	public static Result returned(List<Line> lines, Double refund) {
		BigDecimal linesTtc = lines.stream().map(l -> l.ttc).reduce(BigDecimal.ZERO, BigDecimal::add);
		if (linesTtc.signum() <= 0) {
			return new Result();
		}
		BigDecimal factor = big(refund).divide(linesTtc, SCALE, RoundingMode.HALF_UP);
		Result result = document(lines, factor, false);
		if (result.costed()) {
			result.cost = result.cost.negate();
			result.margin = result.margin.negate();
			result.ht = result.ht.negate();
		}
		return result;
	}

	/** The costed lines' HT (a ticket: TTC before VAT; a return: the line's HT) times the factor, and their cost. */
	private static Result document(List<Line> lines, BigDecimal factor, boolean fromTtc) {
		BigDecimal ht = BigDecimal.ZERO;
		BigDecimal cost = BigDecimal.ZERO;
		long without = 0;
		long costed = 0;
		for (Line line : lines) {
			if (line.stamp) {
				continue;
			}
			BigDecimal unitCost = line.knownCost();
			if (unitCost == null) {
				without++;
				continue;
			}
			costed++;
			BigDecimal lineHt = fromTtc
					? line.ttc.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(100L + rate(line)), SCALE,
							RoundingMode.HALF_UP)
					: line.ht;
			ht = ht.add(lineHt.multiply(factor));
			cost = cost.add(line.quantity.multiply(unitCost));
		}
		Result result = new Result();
		if (costed == 0) {
			return result;
		}
		result.cost = cost;
		result.ht = ht;
		result.margin = ht.subtract(cost);
		result.linesWithoutCost = without;
		result.marginPercent = percent(result.margin, ht, without);
		return result;
	}

	/** A day (or any set of documents): the sums of its costed documents; all null when none is costed. */
	public static Result day(List<Result> documents) {
		Result day = new Result();
		for (Result document : documents) {
			if (document == null || !document.costed()) {
				continue;
			}
			if (!day.costed()) {
				day.cost = BigDecimal.ZERO;
				day.margin = BigDecimal.ZERO;
				day.ht = BigDecimal.ZERO;
				day.linesWithoutCost = 0L;
			}
			day.cost = day.cost.add(document.cost);
			day.margin = day.margin.add(document.margin);
			day.ht = day.ht.add(document.ht);
			day.linesWithoutCost += document.linesWithoutCost;
		}
		if (day.costed()) {
			day.marginPercent = percent(day.margin, day.ht, day.linesWithoutCost);
		}
		return day;
	}

	private static Double percent(BigDecimal margin, BigDecimal ht, long without) {
		BigDecimal base = ht.setScale(3, RoundingMode.HALF_UP);
		if (without > 0 || base.signum() == 0) {
			return null;
		}
		return margin.multiply(BigDecimal.valueOf(100)).divide(ht, 1, RoundingMode.HALF_UP).doubleValue();
	}

	private static int rate(Line line) {
		return line.vatPercent == null ? 0 : line.vatPercent;
	}

	private static BigDecimal big(Double value) {
		return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
	}

	private static Double money(BigDecimal value) {
		return value == null ? null : value.setScale(3, RoundingMode.HALF_UP).doubleValue();
	}
}
