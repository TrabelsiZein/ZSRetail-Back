package com.digithink.zsretail.core.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.digithink.zsretail.core.model.AcceptedTicket;
import com.digithink.zsretail.core.model.BarcodeLayout;
import com.digithink.zsretail.core.model.PaymentMethod;

import lombok.Data;

/**
 * 3.2.0, step 3: reads a scanned ticket's barcode against the accepted tickets, at a date given (the tests and the till
 * pass it; nothing here reads the clock). Pure: no database, nothing stored.
 * <ol>
 * <li>digits only, else NOT_DIGITS;</li>
 * <li>the lines whose code starts the barcode: none, UNKNOWN_TICKET; none of the barcode's length, WRONG_LENGTH;</li>
 * <li>among those of its length the longest code wins (the lines kept by AcceptedTicketService never overlap, so only a
 * hand-edited table can need it);</li>
 * <li>the fields read with the line's layout: face value (millimes, so in TND / 1000), year (2 digits: 20yy), serial;
 * valid until the end of the month that is {@code validityMonths} after December of the printed year (1: 31 January of
 * the next year; null: no check); counted = face value minus the deduction %, to the millime, half up;</li>
 * <li>status: OTHER_METHOD (the line belongs to another method than the one expected), LINE_INACTIVE, EXPIRED (the date
 * after the last valid day), else OK.</li>
 * </ol>
 */
public final class TicketBarcodeParser {

	/** ALREADY_USED (step 4): never given here, by the store's check (a sale holds the barcode). */
	public enum Status {
		OK, NOT_DIGITS, UNKNOWN_TICKET, WRONG_LENGTH, OTHER_METHOD, LINE_INACTIVE, EXPIRED, ALREADY_USED
	}

	static final int MAX_BARCODE_LENGTH = 64;
	static final int MAX_VALUE_DIGITS = 9;

	@Data
	public static class Reading {
		private String barcode;
		private Status status;
		private Long ticketId;
		private Long paymentMethodId;
		private String paymentMethodName;
		private String issuerName;
		private String ticketCode;
		/** WRONG_LENGTH: the length the prefix's line expects. */
		private Integer expectedLength;
		private BigDecimal faceValue;
		private Integer year;
		private String serial;
		private LocalDate validUntil;
		private BigDecimal deductionPercent;
		private BigDecimal countedValue;
		/** Step 4: the sale that holds this barcode (ScannedTicketService), else null. */
		private String usedSaleNumber;
		private java.time.LocalDateTime usedAt;
		private String usedSessionNumber;
		private String usedCashier;
	}

	private TicketBarcodeParser() {
	}

	/** The reading of {@code barcode} at {@code date}; {@code expectedMethodId} null: any method. */
	public static Reading read(String barcode, LocalDate date, Collection<AcceptedTicket> lines, Long expectedMethodId) {
		Reading reading = new Reading();
		String code = barcode == null ? "" : barcode.trim();
		reading.setBarcode(code);
		if (code.isEmpty() || !code.chars().allMatch(Character::isDigit)) {
			reading.setStatus(Status.NOT_DIGITS);
			return reading;
		}
		List<AcceptedTicket> prefixed = lines.stream()
				.filter(line -> line.getTicketCode() != null && !line.getTicketCode().isEmpty()
						&& code.startsWith(line.getTicketCode()))
				.sorted(Comparator.comparingInt((AcceptedTicket line) -> line.getTicketCode().length()).reversed())
				.collect(Collectors.toList());
		if (prefixed.isEmpty()) {
			reading.setStatus(Status.UNKNOWN_TICKET);
			return reading;
		}
		Optional<AcceptedTicket> found = prefixed.stream()
				.filter(line -> line.getLayout() != null && Integer.valueOf(code.length()).equals(line.getLayout().getBarcodeLength())
						&& layoutProblem(line.getTicketCode(), line.getLayout()) == null)
				.findFirst();
		if (!found.isPresent()) {
			AcceptedTicket nearest = prefixed.get(0);
			describe(reading, nearest);
			reading.setExpectedLength(nearest.getLayout() != null ? nearest.getLayout().getBarcodeLength() : null);
			reading.setStatus(Status.WRONG_LENGTH);
			return reading;
		}
		AcceptedTicket line = found.get();
		describe(reading, line);
		BarcodeLayout layout = line.getLayout();
		reading.setFaceValue(new BigDecimal(BarcodeLayout.field(code, layout.getValueStart(), layout.getValueLength()))
				.movePointLeft(3).setScale(3, RoundingMode.UNNECESSARY));
		int year = Integer.parseInt(BarcodeLayout.field(code, layout.getYearStart(), layout.getYearLength()));
		reading.setYear(layout.getYearLength() == 2 ? 2000 + year : year);
		reading.setSerial(BarcodeLayout.field(code, layout.getSerialStart(), layout.getSerialLength()));
		reading.setValidUntil(validUntil(reading.getYear(), line.getValidityMonths()));
		reading.setCountedValue(counted(reading.getFaceValue(), line.getDeductionPercent()));
		if (expectedMethodId != null && !expectedMethodId.equals(reading.getPaymentMethodId())) {
			reading.setStatus(Status.OTHER_METHOD);
		} else if (!Boolean.TRUE.equals(line.getActive())) {
			reading.setStatus(Status.LINE_INACTIVE);
		} else if (reading.getValidUntil() != null && date.isAfter(reading.getValidUntil())) {
			reading.setStatus(Status.EXPIRED);
		} else {
			reading.setStatus(Status.OK);
		}
		return reading;
	}

	private static void describe(Reading reading, AcceptedTicket line) {
		PaymentMethod method = line.getPaymentMethod();
		reading.setTicketId(line.getId());
		reading.setPaymentMethodId(method != null ? method.getId() : null);
		reading.setPaymentMethodName(method != null ? method.getName() : null);
		reading.setIssuerName(line.getIssuerName());
		reading.setTicketCode(line.getTicketCode());
		reading.setDeductionPercent(line.getDeductionPercent());
	}

	/** The last valid day: the end of the month {@code months} after December of {@code year}; null months: none. */
	public static LocalDate validUntil(int year, Integer months) {
		if (months == null) {
			return null;
		}
		return YearMonth.of(year, 12).plusMonths(months).atEndOfMonth();
	}

	/** The face value minus the deduction %, to the millime, half up (no deduction: the face value). */
	public static BigDecimal counted(BigDecimal faceValue, BigDecimal deductionPercent) {
		BigDecimal percent = deductionPercent == null ? BigDecimal.ZERO : deductionPercent;
		return faceValue.multiply(BigDecimal.valueOf(100).subtract(percent)).divide(BigDecimal.valueOf(100), 3,
				RoundingMode.HALF_UP);
	}

	/**
	 * Why a layout cannot read barcodes of the code given, or null: a length between the code's + 1 and 64; the face value
	 * (1 to 9 digits), the year (2 or 4 digits) and the serial (optional: length 0) inside the barcode, after the code,
	 * without overlapping.
	 */
	public static String layoutProblem(String code, BarcodeLayout layout) {
		if (layout == null || layout.getBarcodeLength() == null) {
			return "The barcode length is required.";
		}
		int length = layout.getBarcodeLength();
		int codeLength = code == null ? 0 : code.length();
		if (length <= codeLength || length > MAX_BARCODE_LENGTH) {
			return "The barcode length must be longer than the code and at most " + MAX_BARCODE_LENGTH + ".";
		}
		if (layout.getValueLength() == null || layout.getValueLength() < 1 || layout.getValueLength() > MAX_VALUE_DIGITS) {
			return "The face value has 1 to " + MAX_VALUE_DIGITS + " digits.";
		}
		if (layout.getYearLength() == null || (layout.getYearLength() != 2 && layout.getYearLength() != 4)) {
			return "The year has 2 or 4 digits.";
		}
		List<int[]> fields = new ArrayList<>();
		fields.add(new int[] { 1, codeLength });
		String problem = add(fields, "face value", layout.getValueStart(), layout.getValueLength(), length);
		if (problem == null) {
			problem = add(fields, "year", layout.getYearStart(), layout.getYearLength(), length);
		}
		if (problem == null && layout.getSerialLength() != null && layout.getSerialLength() > 0) {
			problem = add(fields, "serial", layout.getSerialStart(), layout.getSerialLength(), length);
		}
		return problem;
	}

	private static String add(List<int[]> fields, String name, Integer start, int fieldLength, int length) {
		if (start == null || start < 1 || start + fieldLength - 1 > length) {
			return "The " + name + " must be inside the barcode (positions 1 to " + length + ").";
		}
		int end = start + fieldLength - 1;
		for (int[] other : fields) {
			if (start <= other[1] && other[0] <= end) {
				return "The " + name + " overlaps another field (the code is positions 1 to " + fields.get(0)[1] + ").";
			}
		}
		fields.add(new int[] { start, end });
		return null;
	}
}
