package com.digithink.zsretail.erp.spi;

import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;

/**
 * Code cleanup step 3: one barcode row of the ERP as the catalogue engine reads it: the barcode (its value and item
 * trimmed, never blank) and its position, the number the ERP gives each barcode row and that only grows (null when the
 * row has none). The engine keeps a cursor on the positions and, for the same barcode twice, keeps the row of the
 * highest position. ErpItemBarcodeDTO itself is not changed (the Dynamics NAV connector uses it).
 */
public final class ErpBarcodeRow {

	private final ErpItemBarcodeDTO barcode;
	private final Long position;

	public ErpBarcodeRow(ErpItemBarcodeDTO barcode, Long position) {
		this.barcode = barcode;
		this.position = position;
	}

	public ErpItemBarcodeDTO getBarcode() {
		return barcode;
	}

	public Long getPosition() {
		return position;
	}
}
