package com.digithink.zsretail.erp.navpospages.sync;

import java.util.List;

import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;

/**
 * The import of one packet, in its own transaction: the rows and their change rows for the stores commit together
 * (ErpItemBootstrapService.importItems / importItemBarcodes, wrapped by HeadOfficeErpCatalogueRecorder). A packet that
 * fails rolls back alone; the packets committed before it stay.
 */
public interface NavPosPagesImport {

	void items(List<ErpItemDTO> packet);

	void barcodes(List<ErpItemBarcodeDTO> packet);
}
