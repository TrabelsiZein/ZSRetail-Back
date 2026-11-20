package com.digithink.zsretail.core.communication.service;

/**
 * 3.3.0, communication step 4: how many SMS a text makes (the template preview). A text of the GSM 03.38 alphabet only
 * (GSM 7-bit) holds 160 characters in one SMS, 153 a part when it needs more (the rest carries the joining header); the
 * characters of the extension table (^ { } \ [ ~ ] | €) count two. One character outside it (ç, ê, ô, Arabic...) makes
 * the whole text Unicode: 70 in one SMS, 67 a part. An empty text is 0 parts.
 */
public final class SmsParts {

	public static final String GSM7 = "GSM7";
	public static final String UNICODE = "UNICODE";

	/** GSM 03.38 basic alphabet. */
	static final String BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRS"
			+ "TUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

	/** GSM 03.38 extension table: two septets each. */
	static final String EXTENDED = "^{}\\[~]|€\f";

	private final String encoding;
	private final int length;
	private final int parts;

	private SmsParts(String encoding, int length, int parts) {
		this.encoding = encoding;
		this.length = length;
		this.parts = parts;
	}

	public static SmsParts of(String text) {
		String value = text == null ? "" : text;
		int septets = 0;
		boolean gsm = true;
		for (int i = 0; i < value.length() && gsm; i++) {
			char c = value.charAt(i);
			if (BASIC.indexOf(c) >= 0) {
				septets++;
			} else if (EXTENDED.indexOf(c) >= 0) {
				septets += 2;
			} else {
				gsm = false;
			}
		}
		if (gsm) {
			return new SmsParts(GSM7, septets, parts(septets, 160, 153));
		}
		int units = value.length(); // UTF-16 code units, as the SMS counts them (an emoji is two)
		return new SmsParts(UNICODE, units, parts(units, 70, 67));
	}

	private static int parts(int length, int single, int multi) {
		if (length == 0) {
			return 0;
		}
		return length <= single ? 1 : (length + multi - 1) / multi;
	}

	/** GSM7 or UNICODE. */
	public String getEncoding() {
		return encoding;
	}

	/** The characters as the SMS counts them (an extension character counts two in GSM 7-bit). */
	public int getLength() {
		return length;
	}

	public int getParts() {
		return parts;
	}
}
