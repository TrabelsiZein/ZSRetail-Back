package com.digithink.zsretail.connector.zen.client;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.dto.ZenStockPointRow;
import com.digithink.zsretail.connector.zen.mapper.ZenMapper;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCatalogueSource;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemImagesDTO;

/**
 * Item variants (ZEN), step 8: ZEN's API as the catalogue engine reads it, through the mapper. The tree gives the
 * catégories, families, sub-families and sections; the rows of a location are read as the changes since its checkpoint
 * ({@link #readsChangesOnly()}: a row not answered is unchanged, never gone); the barcodes by lastModifiedDateTime
 * (step 12a, the position in epoch milliseconds), their items with the variant. The page before 2026-10-24 had the
 * prices with VAT only: a row with publicPrice but no unitPrice fails the read of its location ({@link #NO_UNIT_PRICE}),
 * nothing applied.
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenCatalogueSource implements ErpCatalogueSource {

	public static final String NO_UNIT_PRICE = "zen: stockPoints has no unitPrice: update the page";

	private final ZenSource pages;
	private final ZenMapper mapper;
	private final ZenProperties properties;
	/** 3.1.1 (item images): ZEN's products API; null: no images read (the tests without it). */
	private final ZenProductsClient products;

	@Autowired
	public ZenCatalogueSource(ZenSource pages, ZenProperties properties, ZenProductsClient products) {
		this(pages, new ZenMapper(properties.getDefaultVat()), properties, products);
	}

	/** Without the products API: used by the tests. */
	public ZenCatalogueSource(ZenSource pages, ZenProperties properties) {
		this(pages, new ZenMapper(properties.getDefaultVat()), properties, null);
	}

	/** With a given mapper, without the products API: used by the tests. */
	public ZenCatalogueSource(ZenSource pages, ZenMapper mapper, ZenProperties properties) {
		this(pages, mapper, properties, null);
	}

	/** With a given mapper and products API: used by the tests. */
	public ZenCatalogueSource(ZenSource pages, ZenMapper mapper, ZenProperties properties, ZenProductsClient products) {
		this.pages = pages;
		this.mapper = mapper;
		this.properties = properties;
		this.products = products;
	}

	// ─── Item images (3.1.1), ZEN's products API ─────────────────

	/** Its address and key are set (erp.zen.products.url and api-key). */
	@Override
	public boolean readsItemImages() {
		return products != null && products.configured();
	}

	@Override
	public List<ErpItemImagesDTO> itemImages(List<String> navItemNos) {
		return products.bySkus(navItemNos);
	}

	@Override
	public String itemImagesLabel() {
		return products == null || products.url() == null ? ZenProductsClient.LABEL
				: ZenProductsClient.LABEL + " " + products.url();
	}

	@Override
	public ErpCategories categories() {
		return mapper.categories(pages.readCategories());
	}

	/** Every row of the location (a whole read). */
	@Override
	public ErpRead<ErpItemDTO> items(String locationCode) {
		return items(locationCode, null);
	}

	/**
	 * The rows of the location modified at or after since (null: every row). A row of the old page (publicPrice, no
	 * unitPrice) fails the read: its price with VAT would be sold as the price before VAT.
	 */
	@Override
	public ErpRead<ErpItemDTO> items(String locationCode, OffsetDateTime since) {
		List<ZenStockPointRow> rows = pages.readStockPoints(locationCode, since);
		if (rows.stream().anyMatch(row -> row.getPublicPrice() != null && row.getUnitPrice() == null)) {
			throw new ZenReadException(pageLabel(Part.ITEMS), NO_UNIT_PRICE, null);
		}
		return mapper.items(locationCode, rows);
	}

	@Override
	public boolean readsChangesOnly() {
		return true;
	}

	@Override
	public ErpRead<ErpBarcodeRow> barcodesAfter(long position) {
		return mapper.barcodeRows(pages.readBarcodesAfter(position));
	}

	/** Every barcode of these items (codes itemNo~variantCode), the rows of other items or variants left out. */
	@Override
	public ErpRead<ErpBarcodeRow> barcodesOfItems(List<String> itemCodes) {
		ErpRead<ErpBarcodeRow> read = mapper.barcodeRows(pages.readBarcodesOfItems(itemCodes));
		Set<String> asked = new HashSet<>(itemCodes);
		List<ErpBarcodeRow> rows = read.getRows().stream()
				.filter(row -> asked.contains(row.getBarcode().getItemExternalId())).collect(Collectors.toList());
		return new ErpRead<>(rows, read.getRead(), read.getLeftOut(), read.getPositions());
	}

	@Override
	public String pageLabel(Part part) {
		switch (part) {
		case CATEGORIES:
			return properties.getPage().getCategories();
		case ITEMS:
			return properties.getPage().getStockPoints();
		case BARCODES:
			return properties.getPage().getBarcodes();
		default:
			throw new IllegalArgumentException(String.valueOf(part));
		}
	}

	@Override
	public String pageUrl(Part part) {
		return pages.pageUrl(pageLabel(part));
	}
}
