package com.digithink.zsretail.erp.navpospages.sync;

import java.util.List;

import com.digithink.zsretail.erp.dto.ErpItemDTO;

/**
 * The import of one packet of items, in its own transaction: the items and their change rows for the stores commit
 * together (ErpItemBootstrapService.importItems, wrapped by HeadOfficeErpCatalogueRecorder). A packet that fails rolls
 * back alone; the packets committed before it stay.
 */
@FunctionalInterface
public interface NavPosPagesImport {

	void items(List<ErpItemDTO> packet);
}
