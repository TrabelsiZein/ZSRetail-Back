package com.digithink.zsretail.erp.api;

import java.time.OffsetDateTime;
import java.util.List;

import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemImagesDTO;

/**
 * Code cleanup step 3: the reads of the ERP's catalogue as the catalogue engine uses them (GET only), already
 * translated to the Erp*DTO classes. A connector implements it with its own client and mapper (which pages, which
 * fields); the engine compares, waits, keeps the barcode cursor and applies. Each read says how many rows it read and
 * which it left out, by reason (the connector's reasons, shown in the summary of the run). 3.1.1: the items' images,
 * optional (a connector may read them from another system than its ERP, e.g. ZEN's products API, a POST that reads).
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
	 * Item variants (ZEN), step 2: the items of one location changed since this time (null: every item, as
	 * {@link #items(String)}). By default every item, whatever the time: a connector that reads changes only overrides
	 * it and {@link #readsChangesOnly()}.
	 */
	default ErpRead<ErpItemDTO> items(String locationCode, OffsetDateTime since) {
		return items(locationCode);
	}

	/**
	 * Item variants (ZEN), step 2: true when {@link #items(String, OffsetDateTime)} with a time answers only the rows
	 * changed since then (a row not answered is unchanged, not gone). False by default: each read is the whole location.
	 */
	default boolean readsChangesOnly() {
		return false;
	}

	/**
	 * One page of barcodes whose position is above this one; an empty read (0 rows read) means caught up. The positions
	 * of every row read, left out ones included, are in {@link ErpRead#getPositions()}.
	 */
	ErpRead<ErpBarcodeRow> barcodesAfter(long position);

	/** Every barcode of these items (a few item codes per call). */
	ErpRead<ErpBarcodeRow> barcodesOfItems(List<String> itemCodes);

	/**
	 * 3.1.1 (item images): true when the connector reads the items' images (its settings for them are set). False by
	 * default: the catalogue job then skips its images part.
	 */
	default boolean readsItemImages() {
		return false;
	}

	/**
	 * 3.1.1: the images of these ERP items (NAV item numbers, at most {@link #ITEM_IMAGES_PER_CALL} per call); an item the
	 * ERP does not know is not answered.
	 */
	default List<ErpItemImagesDTO> itemImages(List<String> navItemNos) {
		throw new ErpNotSupportedException("item images");
	}

	/** 3.1.1: the images of the ERP items changed between these two times. */
	default List<ErpItemImagesDTO> itemImagesChanged(OffsetDateTime from, OffsetDateTime to) {
		throw new ErpNotSupportedException("item images");
	}

	/** 3.1.1: what the images are read from, shown in the summary of the run (no query, never a key). */
	default String itemImagesLabel() {
		return "item images";
	}

	/** 3.1.1: the item numbers asked per call of {@link #itemImages(List)}. */
	int ITEM_IMAGES_PER_CALL = 100;

	/** The name of the ERP page or table read for this part, shown in the summary of the run. */
	String pageLabel(Part part);

	/** The address read for this part, for the communications log (no query, never a password). */
	String pageUrl(Part part);
}
