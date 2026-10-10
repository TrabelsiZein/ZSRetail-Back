package com.digithink.zsretail.utils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Quantities with up to 3 decimals (2.2.1: bulk items sold by the litre or the kilo, 0.2 or 0.058 at the till). The
 * columns are DECIMAL(18,3) ({@link #PRECISION}, {@link #SCALE}); a whole quantity keeps travelling and printing as
 * before: {@link #normalize} writes 2.000 as 2, so JSON carries "2", never "2.000".
 */
public final class Quantities {

	/** Digits of a quantity column. */
	public static final int PRECISION = 18;

	/** Decimals of a quantity column, and the most a quantity may carry. */
	public static final int SCALE = 3;

	private static final MathContext FIFTEEN_DIGITS = new MathContext(15, RoundingMode.HALF_UP);

	private Quantities() {
	}

	/** The quantity without trailing zeros, never in exponent form: 2.000 is 2, 0.200 is 0.2, 100 stays 100. */
	public static BigDecimal normalize(BigDecimal quantity) {
		if (quantity == null) {
			return null;
		}
		BigDecimal stripped = quantity.stripTrailingZeros();
		return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
	}

	public static BigDecimal of(int quantity) {
		return BigDecimal.valueOf(quantity);
	}

	public static BigDecimal of(Integer quantity) {
		return quantity == null ? null : BigDecimal.valueOf(quantity);
	}

	/** True when the quantity has no decimal part (2.000 is whole). Null is whole: there is nothing to refuse. */
	public static boolean isWhole(BigDecimal quantity) {
		return quantity == null || quantity.signum() == 0 || quantity.stripTrailingZeros().scale() <= 0;
	}

	/** Decimals the quantity really carries (0.050 carries 2). */
	public static int decimals(BigDecimal quantity) {
		return quantity == null ? 0 : Math.max(0, quantity.stripTrailingZeros().scale());
	}

	/** The plain writing, without trailing zeros: "2", "0.2", "0.058"; empty for null. */
	public static String plain(BigDecimal quantity) {
		return quantity == null ? "" : normalize(quantity).toPlainString();
	}

	/**
	 * A place not converted to decimal quantities yet: the quantity as a whole number, or an exception naming the place
	 * when it has decimals (never a silent truncation). Null stays null.
	 */
	public static Integer wholeOrFail(BigDecimal quantity, String place) {
		if (quantity == null) {
			return null;
		}
		if (!isWhole(quantity)) {
			throw new IllegalStateException(place + ": quantity " + plain(quantity)
					+ " is not a whole number, and decimal quantities are not supported here yet");
		}
		return quantity.stripTrailingZeros().intValueExact();
	}

	/**
	 * The same for a value read from a query row (an int column gives an Integer or a Long, a quantity column a
	 * BigDecimal): a long, or an exception naming the place when the value has decimals. Null stays null.
	 */
	public static Long wholeLongOrFail(Object value, String place) {
		if (value == null) {
			return null;
		}
		if (value instanceof BigDecimal) {
			BigDecimal quantity = (BigDecimal) value;
			if (!isWhole(quantity)) {
				throw new IllegalStateException(place + ": quantity " + plain(quantity)
						+ " is not a whole number, and decimal quantities are not supported here yet");
			}
			return quantity.stripTrailingZeros().longValueExact();
		}
		return ((Number) value).longValue();
	}

	/** {@link #wholeLongOrFail} as an int (row values of quantities and stocks). */
	public static Integer wholeIntOrFail(Object value, String place) {
		Long whole = wholeLongOrFail(value, place);
		return whole == null ? null : Math.toIntExact(whole);
	}

	/**
	 * An amount rounded to 3 decimals (millimes), half up. The product is first read to 15 significant digits so that
	 * 1.0005 stored as 1.000499... still rounds up. The till rounds with the same steps (src/libs/quantity.js), so both
	 * sides find the same double.
	 */
	public static double roundAmount(double amount) {
		BigDecimal milli = new BigDecimal(amount * 1000).round(FIFTEEN_DIGITS).setScale(0, RoundingMode.HALF_UP);
		return milli.doubleValue() / 1000;
	}

	/**
	 * The one place where a unit amount is multiplied by a quantity for a sale line. A whole quantity gives exactly the
	 * product of 2.2.0 (unit amount times the whole number, not rounded); a decimal quantity gives the product rounded to
	 * 3 decimals.
	 */
	public static double lineAmount(double unitAmount, BigDecimal quantity) {
		double product = unitAmount * quantity.doubleValue();
		return isWhole(quantity) ? product : roundAmount(product);
	}

	/**
	 * 2.2.1, step 5: the refusal of a writer not converted yet (returns, invoices, purchases, compositions, stock
	 * adjustments, the head office's own BLs and supply invoices) when it meets a quantity with decimals: it names the
	 * item and the place, and nothing is written (never a rounded value).
	 */
	public static String notSupportedYet(String itemCode, String itemName, BigDecimal quantity, String where) {
		String item = itemCode == null ? "?" : itemCode;
		if (itemName != null && !itemName.trim().isEmpty()) {
			item += " (" + itemName.trim() + ")";
		}
		return "Item " + item + ": the quantity " + plain(quantity) + " has decimals, and decimal quantities are not"
				+ " supported in " + where + " yet.";
	}
}
