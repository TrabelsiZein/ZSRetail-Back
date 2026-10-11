package com.digithink.zsretail.erp.spi;

import java.util.List;

import com.digithink.zsretail.erp.dto.ErpItemDTO;

/**
 * Code cleanup step 3: the reads of the ERP's catalogue as the catalogue engine uses them (GET only), already
 * translated to the Erp*DTO classes. A connector implements it with its own client and mapper (which pages, which
 * fields); the engine compares, waits, keeps the barcode cursor and applies. Each read says how many rows it read and
 * which it left out, by reason (the connector's reasons, shown in the summary of the run).
 */
public interface ErpCatalogueSource {

	/** The parts of the catalogue, for the page label and address of a run. */
	enum Part {
		CATEGORIES, ITEMS, BARCODES
	}

	/** Every category, as families and sub-families from one read. */
	ErpCategories categories();

	/** Every item of one location (a point de stock: its code in the ERP). */
	ErpRead<ErpItemDTO> items(String locationCode);

	/**
	 * One page of barcodes whose position is above this one; an empty read (0 rows read) means caught up. The positions
	 * of every row read, left out ones included, are in {@link ErpRead#getPositions()}.
	 */
	ErpRead<ErpBarcodeRow> barcodesAfter(long position);

	/** Every barcode of these items (a few item codes per call). */
	ErpRead<ErpBarcodeRow> barcodesOfItems(List<String> itemCodes);

	/** The name of the ERP page or table read for this part, shown in the summary of the run. */
	String pageLabel(Part part);

	/** The address read for this part, for the communications log (no query, never a password). */
	String pageUrl(Part part);
}
