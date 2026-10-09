package com.digithink.zsretail.erp.navpospages.reader;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesRestClient;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.mapper.NavPosPagesMapper;
import com.digithink.zsretail.erp.navpospages.mapper.NavPosResult;

/**
 * ERP catalogue, step 5: reads a page and translates it (client + mapper). Used by the live read test now and by the
 * connector at step 6. Each call reads the ERP again; nothing is kept between calls.
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
public class NavPosPagesReader {

	private final NavPosPagesRestClient client;
	private final NavPosPagesMapper mapper;

	@Autowired
	public NavPosPagesReader(NavPosPagesRestClient client, NavPosPagesProperties properties) {
		this(client, new NavPosPagesMapper(properties.getDefaultVat(), properties.getPriceIncludesVat()));
	}

	/** With a given mapper: used by the tests. */
	public NavPosPagesReader(NavPosPagesRestClient client, NavPosPagesMapper mapper) {
		this.client = client;
		this.mapper = mapper;
	}

	public NavPosResult<ErpItemFamilyDTO> readFamilies() {
		return mapper.families(client.readCategories());
	}

	public NavPosResult<ErpItemSubFamilyDTO> readSubFamilies() {
		return mapper.subFamilies(client.readCategories());
	}

	/** The items of one stock point (its Location_Code). */
	public NavPosResult<ErpItemDTO> readItems(String locationCode) {
		return mapper.items(client.readItems(locationCode));
	}

	/** One page of barcodes after entryNo; ask again from getHighestEntryNo() while it is not null. */
	public NavPosResult<ErpItemBarcodeDTO> readBarcodesAfter(long entryNo) {
		return mapper.barcodes(client.readBarcodesAfter(entryNo));
	}
}
