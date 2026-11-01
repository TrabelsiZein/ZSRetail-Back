package com.digithink.zsretail.connector.parafendri.client;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.connector.parafendri.config.ParafendriHeadOfficeCondition;
import com.digithink.zsretail.connector.parafendri.mapper.ParafendriMapper;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCatalogueSource;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * Head office catalogue: ParaFendri's API as the catalogue engine reads it (job SYNC_CATALOGUE on a head office whose
 * catalogue comes from the ERP, its points de stock the six points de vente), through the client and the mapper of the
 * store's steps 2 to 4: the tree itemCategories (catégories, familles, sous-familles, sections); the rows of one point
 * de stock from stockPoints, the changes since its checkpoint ({@link #readsChangesOnly()}: lastModifiedDateTime ge, a
 * row not answered is unchanged, never gone), the prices before VAT as received, a blocked row inactive; the barcodes
 * of barCodes by lastModifiedDateTime (ZEN's contract: the position in epoch milliseconds, gt, the last millisecond read
 * whole, a row once by its GUID id) and by item. No item images. Only on such a head office
 * ({@link ParafendriHeadOfficeCondition}): a store keeps its job IMPORT_CATALOGUE.
 */
@Component
@ConditionalOnErpConnector(ParafendriConnectorDescriptor.NAME)
@Conditional(ParafendriHeadOfficeCondition.class)
public class ParafendriCatalogueSource implements ErpCatalogueSource {

	private final ParafendriRestClient client;
	private final ParafendriMapper mapper = new ParafendriMapper();

	public ParafendriCatalogueSource(ParafendriRestClient client) {
		this.client = client;
	}

	@Override
	public ErpCategories categories() {
		return mapper.categories(client.readItemCategories());
	}

	/** Every row of the point de stock (a whole read). */
	@Override
	public ErpRead<ErpItemDTO> items(String locationCode) {
		return items(locationCode, null);
	}

	/**
	 * The rows of the point de stock modified at or after since (null: every row), against the tree read again (121
	 * rows): a code it does not keep is noted and not linked, as on a store.
	 */
	@Override
	public ErpRead<ErpItemDTO> items(String locationCode, OffsetDateTime since) {
		ErpCategories tree = categories();
		return mapper.items(locationCode, client.readStockPoints(locationCode, since), tree);
	}

	@Override
	public boolean readsChangesOnly() {
		return true;
	}

	@Override
	public ErpRead<ErpBarcodeRow> barcodesAfter(long position) {
		return mapper.barcodeRows(client.readBarcodesAfter(position));
	}

	/** Every barcode of these items (20 per GET), the rows of other items left out. */
	@Override
	public ErpRead<ErpBarcodeRow> barcodesOfItems(List<String> itemCodes) {
		ErpRead<ErpBarcodeRow> read = mapper.barcodeRows(client.readBarcodesOfItems(itemCodes));
		Set<String> asked = itemCodes.stream().map(String::trim).collect(Collectors.toCollection(HashSet::new));
		List<ErpBarcodeRow> rows = read.getRows().stream()
				.filter(row -> asked.contains(row.getBarcode().getItemExternalId())).collect(Collectors.toList());
		return new ErpRead<>(rows, read.getRead(), read.getLeftOut(), read.getPositions());
	}

	@Override
	public String pageLabel(Part part) {
		switch (part) {
		case CATEGORIES:
			return ParafendriRestClient.ITEM_CATEGORIES;
		case ITEMS:
			return ParafendriRestClient.STOCK_POINTS;
		case BARCODES:
			return ParafendriRestClient.BARCODES;
		default:
			throw new IllegalArgumentException(String.valueOf(part));
		}
	}

	@Override
	public String pageUrl(Part part) {
		return client.pageAddress(pageLabel(part));
	}
}
