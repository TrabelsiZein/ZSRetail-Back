package com.digithink.zsretail.core.model;

import javax.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, step 3: where an accepted ticket's barcode keeps its fields, positions from 1. The ticket code is the barcode's
 * prefix (positions 1 to its length), so it has no position of its own here. The face value is in millimes; the year has
 * 2 digits (20yy) or 4; the serial may be absent (length 0). The default, from real tickets: 28 digits, face value 4-9,
 * year 10-11, serial 12-28.
 */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BarcodeLayout {

	private Integer barcodeLength;
	private Integer valueStart;
	private Integer valueLength;
	private Integer yearStart;
	private Integer yearLength;
	private Integer serialStart;
	private Integer serialLength;

	/** 28 digits: code 1-3 (the prefix), face value 4-9 in millimes, year 10-11, serial 12-28. */
	public static BarcodeLayout standard() {
		return new BarcodeLayout(28, 4, 6, 10, 2, 12, 17);
	}

	/** The digits of a field (start from 1, length), or null without a field; the barcode is long enough. */
	public static String field(String barcode, Integer start, Integer length) {
		if (start == null || length == null || length <= 0) {
			return null;
		}
		return barcode.substring(start - 1, start - 1 + length);
	}
}
