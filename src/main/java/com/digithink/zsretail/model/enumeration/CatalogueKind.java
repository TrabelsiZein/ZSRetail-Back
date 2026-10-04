package com.digithink.zsretail.model.enumeration;

/**
 * Head office plan, step 6: the four kinds of catalogue records that travel down as copies (domain CATALOGUE), in the
 * order a store applies them (a sub-family needs its family, an item its family and sub-family, a barcode its item). The
 * record code of the copies down is the prefix and the business code: FAMILY:F1, SUBFAMILY:SF1, ITEM:B001,
 * BARCODE:6191234567890.
 */
public enum CatalogueKind {
	FAMILY, SUBFAMILY, ITEM, BARCODE;

	/** Longest business code that fits in a record code (record_code is 100 characters; the longest prefix is 10). */
	public static final int MAX_CODE_LENGTH = 90;

	/** The system item every installation creates by itself (ZZDataInitializer): never sent, never overwritten. */
	public static final String TAX_STAMP_CODE = "TAX_STAMP";

	public String prefix() {
		return name() + ":";
	}

	/** FAMILY:F1 for (FAMILY, F1). */
	public String recordCode(String code) {
		return prefix() + code;
	}

	/** The kind of a record code; null when it has none of the four prefixes. */
	public static CatalogueKind ofRecordCode(String recordCode) {
		if (recordCode == null) {
			return null;
		}
		for (CatalogueKind kind : values()) {
			if (recordCode.startsWith(kind.prefix())) {
				return kind;
			}
		}
		return null;
	}

	/** The business code of a record code: F1 for FAMILY:F1; null when it has no known prefix. */
	public static String codeOf(String recordCode) {
		CatalogueKind kind = ofRecordCode(recordCode);
		return kind == null ? null : recordCode.substring(kind.prefix().length());
	}
}
