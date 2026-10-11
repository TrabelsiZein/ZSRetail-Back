package com.digithink.zsretail.erp.engine;

import java.util.List;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnErpAbility;
import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.service.ErpItemBootstrapService;

/**
 * {@link CatalogueImport} through the Spring proxy of ErpItemBootstrapService: each call is one transaction (the
 * import's @Transactional, and around it the recorder aspect of a head office whose catalogue comes from the ERP).
 * Called outside any transaction (the job runner opens none), so each packet commits on its own.
 * Code cleanup step 3: was BootstrapNavPosPagesImport of the navpospages connector, now shared by the catalogue engine.
 */
@Component
@ConditionalOnErpAbility(ErpAbility.HEAD_OFFICE_STOCK_POINTS)
public class BootstrapCatalogueImport implements CatalogueImport {

	private final ErpItemBootstrapService bootstrap;

	public BootstrapCatalogueImport(ErpItemBootstrapService bootstrap) {
		this.bootstrap = bootstrap;
	}

	@Override
	public void items(List<ErpItemDTO> packet) {
		bootstrap.importItems(packet);
	}

	@Override
	public void barcodes(List<ErpItemBarcodeDTO> packet) {
		bootstrap.importItemBarcodes(packet);
	}

	@Override
	public void families(List<ErpItemFamilyDTO> packet) {
		bootstrap.importItemFamilies(packet);
	}

	@Override
	public void subFamilies(List<ErpItemSubFamilyDTO> packet) {
		bootstrap.importItemSubFamilies(packet);
	}
}
