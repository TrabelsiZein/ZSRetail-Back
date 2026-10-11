package com.digithink.zsretail.erp.engine;

import java.util.List;

import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;

/**
 * The import of one packet, in its own transaction: the rows and their change rows for the stores commit together
 * (ErpItemBootstrapService.importItems / importItemBarcodes, wrapped by HeadOfficeErpCatalogueRecorder). A packet that
 * fails rolls back alone; the packets committed before it stay.
 * Code cleanup step 3: was NavPosPagesImport of the navpospages connector, now shared by the catalogue engine.
 */
public interface CatalogueImport {

	void items(List<ErpItemDTO> packet);

	void barcodes(List<ErpItemBarcodeDTO> packet);

	/** Release 2.2, the job SYNC_CATALOGUE: one packet of families (ErpItemBootstrapService.importItemFamilies). */
	default void families(List<ErpItemFamilyDTO> packet) {
		throw new UnsupportedOperationException("families");
	}

	/** Release 2.2, the job SYNC_CATALOGUE: one packet of sub-families (importItemSubFamilies). */
	default void subFamilies(List<ErpItemSubFamilyDTO> packet) {
		throw new UnsupportedOperationException("subFamilies");
	}
}
