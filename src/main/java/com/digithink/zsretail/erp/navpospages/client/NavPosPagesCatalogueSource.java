package com.digithink.zsretail.erp.navpospages.client;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.mapper.NavPosPagesMapper;
import com.digithink.zsretail.erp.spi.ErpBarcodeRow;
import com.digithink.zsretail.erp.spi.ErpCatalogueSource;
import com.digithink.zsretail.erp.spi.ErpCategories;
import com.digithink.zsretail.erp.spi.ErpRead;

/**
 * Code cleanup step 3: the thin part of the navpospages connector as the catalogue engine reads it: the "POS pages"
 * (the REST client, or a fake in the tests) through the mapper. The categories page gives the families and the
 * sub-families; the items page is read per point de stock (its Location_Code); the barcode position is the Entry_No.
 * The source the catalogue engine reads on this connector.
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
public class NavPosPagesCatalogueSource implements ErpCatalogueSource {

	private final NavPosPagesSource pages;
	private final NavPosPagesMapper mapper;
	private final NavPosPagesProperties properties;

	@Autowired
	public NavPosPagesCatalogueSource(NavPosPagesSource pages, NavPosPagesProperties properties) {
		this(pages, new NavPosPagesMapper(properties.getDefaultVat(), properties.getPriceIncludesVat()), properties);
	}

	/** With a given mapper: used by the tests. */
	public NavPosPagesCatalogueSource(NavPosPagesSource pages, NavPosPagesMapper mapper,
			NavPosPagesProperties properties) {
		this.pages = pages;
		this.mapper = mapper;
		this.properties = properties;
	}

	@Override
	public ErpCategories categories() {
		List<NavPosCategoryRow> rows = pages.readCategories();
		return new ErpCategories(mapper.families(rows), mapper.subFamilies(rows));
	}

	@Override
	public ErpRead<ErpItemDTO> items(String locationCode) {
		return mapper.items(pages.readItems(locationCode));
	}

	@Override
	public ErpRead<ErpBarcodeRow> barcodesAfter(long position) {
		return mapper.barcodeRows(pages.readBarcodesAfter(position));
	}

	@Override
	public ErpRead<ErpBarcodeRow> barcodesOfItems(List<String> itemCodes) {
		return mapper.barcodeRows(pages.readBarcodesOfItems(itemCodes));
	}

	@Override
	public String pageLabel(Part part) {
		switch (part) {
		case CATEGORIES:
			return properties.getPage().getCategories();
		case ITEMS:
			return properties.getPage().getItems();
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
