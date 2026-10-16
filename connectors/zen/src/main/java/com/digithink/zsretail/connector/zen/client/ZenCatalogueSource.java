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
import com.digithink.zsretail.connector.zen.mapper.ZenMapper;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCatalogueSource;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * Item variants (ZEN), step 8: ZEN's API as the catalogue engine reads it, through the mapper. The tree gives the
 * catégories, families, sub-families and sections; the rows of a location are read as the changes since its checkpoint
 * ({@link #readsChangesOnly()}: a row not answered is unchanged, never gone); the barcodes by lastModifiedDateTime
 * (step 12a, the position in epoch milliseconds), their items with the variant.
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenCatalogueSource implements ErpCatalogueSource {

	private final ZenSource pages;
	private final ZenMapper mapper;
	private final ZenProperties properties;

	@Autowired
	public ZenCatalogueSource(ZenSource pages, ZenProperties properties) {
		this(pages, new ZenMapper(properties.getDefaultVat()), properties);
	}

	/** With a given mapper: used by the tests. */
	public ZenCatalogueSource(ZenSource pages, ZenMapper mapper, ZenProperties properties) {
		this.pages = pages;
		this.mapper = mapper;
		this.properties = properties;
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

	/** The rows of the location modified at or after since (null: every row). */
	@Override
	public ErpRead<ErpItemDTO> items(String locationCode, OffsetDateTime since) {
		return mapper.items(locationCode, pages.readStockPoints(locationCode, since));
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
