package com.digithink.zsretail.analytics.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.analytics.dto.AnalyticsSummaryDTO;
import com.digithink.zsretail.analytics.dto.PaymentBreakdownDTO;
import com.digithink.zsretail.analytics.dto.SalesTrendPointDTO;
import com.digithink.zsretail.analytics.dto.TopProductDTO;
import com.digithink.zsretail.analytics.repository.AnalyticsRepository;

@Service
public class AnalyticsService {

	private static final int MONEY_SCALE = 3;
	private static final int PERCENT_SCALE = 2;

	@Autowired
	private AnalyticsRepository analyticsRepository;

	public AnalyticsSummaryDTO getSummary(LocalDate from, LocalDate to) {
		validatePeriod(from, to);

		LocalDate prevFrom = computePreviousFrom(from, to);
		LocalDate prevTo = from.minusDays(1);

		Object[] salesNow = analyticsRepository.getSalesSummary(from, to);
		Object[] returnsNow = analyticsRepository.getReturnsSummary(from, to);
		Object[] salesPrev = analyticsRepository.getSalesSummary(prevFrom, prevTo);
		Object[] returnsPrev = analyticsRepository.getReturnsSummary(prevFrom, prevTo);

		BigDecimal revenueNow = normalizeMoney(getBigDecimalAt(salesNow, 0));
		long transactionCountNow = getLongAt(salesNow, 1);
		BigDecimal totalReturnsNow = normalizeMoney(getBigDecimalAt(returnsNow, 0));

		BigDecimal revenuePrev = normalizeMoney(getBigDecimalAt(salesPrev, 0));
		long transactionCountPrev = getLongAt(salesPrev, 1);
		BigDecimal totalReturnsPrev = normalizeMoney(getBigDecimalAt(returnsPrev, 0));

		BigDecimal avgBasketNow = computeAvgBasket(revenueNow, transactionCountNow);
		BigDecimal avgBasketPrev = computeAvgBasket(revenuePrev, transactionCountPrev);

		BigDecimal netRevenueNow = normalizeMoney(revenueNow.subtract(totalReturnsNow));
		BigDecimal netRevenuePrev = normalizeMoney(revenuePrev.subtract(totalReturnsPrev));

		return AnalyticsSummaryDTO.builder()
				.revenue(revenueNow)
				.revenueDelta(computeDeltaPercent(revenueNow, revenuePrev))
				.transactionCount(transactionCountNow)
				.transactionCountDelta(computeDeltaPercent(transactionCountNow, transactionCountPrev))
				.avgBasket(avgBasketNow)
				.avgBasketDelta(computeDeltaPercent(avgBasketNow, avgBasketPrev))
				.totalReturns(totalReturnsNow)
				.totalReturnsDelta(computeDeltaPercent(totalReturnsNow, totalReturnsPrev))
				.netRevenue(netRevenueNow)
				.netRevenueDelta(computeDeltaPercent(netRevenueNow, netRevenuePrev))
				.build();
	}

	public List<SalesTrendPointDTO> getSalesTrend(LocalDate from, LocalDate to) {
		validatePeriod(from, to);

		List<Object[]> rows = analyticsRepository.getSalesTrend(from, to);
		if (rows == null || rows.isEmpty()) {
			return Collections.emptyList();
		}

		List<SalesTrendPointDTO> result = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			LocalDate date = toLocalDate(row[0]);
			BigDecimal revenue = normalizeMoney(toBigDecimal(row[1]));
			long transactionCount = toLong(row[2]);

			result.add(SalesTrendPointDTO.builder()
					.date(date)
					.revenue(revenue)
					.transactionCount(transactionCount)
					.build());
		}

		return result;
	}

	public List<TopProductDTO> getTopProducts(LocalDate from, LocalDate to, int limit) {
		validatePeriod(from, to);
		if (limit <= 0) {
			return Collections.emptyList();
		}

		List<Object[]> rows = analyticsRepository.getTopProducts(from, to, limit);
		if (rows == null || rows.isEmpty()) {
			return Collections.emptyList();
		}

		List<TopProductDTO> result = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			String itemCode = (String) row[0];
			String itemName = (String) row[1];
			String familyName = (String) row[2];
			long quantitySold = toLong(row[3]);
			BigDecimal revenue = normalizeMoney(toBigDecimal(row[4]));

			result.add(TopProductDTO.builder()
					.itemCode(itemCode)
					.itemName(itemName)
					.familyName(familyName)
					.quantitySold(quantitySold)
					.revenue(revenue)
					.build());
		}

		return result;
	}

	public List<PaymentBreakdownDTO> getPaymentBreakdown(LocalDate from, LocalDate to) {
		validatePeriod(from, to);

		List<Object[]> rows = analyticsRepository.getPaymentBreakdown(from, to);
		if (rows == null || rows.isEmpty()) {
			return Collections.emptyList();
		}

		List<PaymentBreakdownDTO> result = new ArrayList<>(rows.size());

		BigDecimal sum = BigDecimal.ZERO;
		for (Object[] row : rows) {
			sum = sum.add(normalizeMoney(toBigDecimal(row[2])));
		}

		for (Object[] row : rows) {
			String methodCode = (String) row[0];
			String methodName = (String) row[1];
			BigDecimal totalAmount = normalizeMoney(toBigDecimal(row[2]));

			BigDecimal percentage;
			if (sum.compareTo(BigDecimal.ZERO) == 0) {
				percentage = BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
			} else {
				percentage = totalAmount
						.divide(sum, PERCENT_SCALE, RoundingMode.HALF_UP)
						.multiply(BigDecimal.valueOf(100))
						.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
			}

			result.add(PaymentBreakdownDTO.builder()
					.methodCode(methodCode)
					.methodName(methodName)
					.totalAmount(totalAmount)
					.percentage(percentage)
					.build());
		}

		return result;
	}

	private static void validatePeriod(LocalDate from, LocalDate to) {
		if (from == null || to == null) {
			throw new IllegalArgumentException("from and to must be provided");
		}
		if (from.isAfter(to)) {
			throw new IllegalArgumentException("from must be <= to");
		}
	}

	/**
	 * Previous period = immediately preceding same duration.
	 * Example: if current is [2026-03-01..2026-03-07] (7 days),
	 * previous is [2026-02-22..2026-02-28].
	 */
	private static LocalDate computePreviousFrom(LocalDate from, LocalDate to) {
		long durationDays = ChronoUnit.DAYS.between(from, to) + 1;
		return from.minusDays(durationDays);
	}

	private static BigDecimal computeAvgBasket(BigDecimal revenue, long transactionCount) {
		if (transactionCount <= 0) {
			return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
		}
		return revenue.divide(BigDecimal.valueOf(transactionCount), MONEY_SCALE, RoundingMode.HALF_UP);
	}

	private static BigDecimal computeDeltaPercent(BigDecimal current, BigDecimal previous) {
		if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
			return BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
		}
		BigDecimal delta = current.subtract(previous)
				.multiply(BigDecimal.valueOf(100))
				.divide(previous, PERCENT_SCALE, RoundingMode.HALF_UP);
		return delta.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
	}

	private static BigDecimal computeDeltaPercent(long current, long previous) {
		if (previous <= 0) {
			return BigDecimal.ZERO.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
		}
		BigDecimal delta = BigDecimal.valueOf(current - previous)
				.multiply(BigDecimal.valueOf(100))
				.divide(BigDecimal.valueOf(previous), PERCENT_SCALE, RoundingMode.HALF_UP);
		return delta.setScale(PERCENT_SCALE, RoundingMode.HALF_UP);
	}

	private static BigDecimal normalizeMoney(BigDecimal value) {
		if (value == null) {
			return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
		}
		return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
	}

	private static BigDecimal toBigDecimal(Object value) {
		if (value == null) {
			return BigDecimal.ZERO;
		}
		if (value instanceof BigDecimal) {
			return (BigDecimal) value;
		}
		if (value instanceof Number) {
			return new BigDecimal(value.toString());
		}
		throw new IllegalArgumentException("Unexpected numeric type: " + value.getClass());
	}

	private static long toLong(Object value) {
		if (value == null) {
			return 0L;
		}
		if (value instanceof Number) {
			return ((Number) value).longValue();
		}
		if (value instanceof BigDecimal) {
			return ((BigDecimal) value).longValue();
		}
		throw new IllegalArgumentException("Unexpected count type: " + value.getClass());
	}

	private static BigDecimal getBigDecimalAt(Object[] row, int index) {
		if (row == null || row.length <= index) {
			return BigDecimal.ZERO;
		}
		return toBigDecimal(row[index]);
	}

	private static long getLongAt(Object[] row, int index) {
		if (row == null || row.length <= index) {
			return 0L;
		}
		return toLong(row[index]);
	}

	private static LocalDate toLocalDate(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof LocalDate) {
			return (LocalDate) value;
		}
		if (value instanceof Date) {
			return ((Date) value).toLocalDate();
		}
		if (value instanceof LocalDateTime) {
			return ((LocalDateTime) value).toLocalDate();
		}
		if (value instanceof String) {
			try {
				return LocalDate.parse((String) value);
			} catch (DateTimeParseException e) {
				throw new IllegalArgumentException("Invalid date string: " + value, e);
			}
		}
		throw new IllegalArgumentException("Unexpected date type: " + value.getClass());
	}
}

