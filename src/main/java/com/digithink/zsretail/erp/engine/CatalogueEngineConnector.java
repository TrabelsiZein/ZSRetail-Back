package com.digithink.zsretail.erp.engine;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpSyncFilter;
import com.digithink.zsretail.erp.dto.PullOperationResult;
import com.digithink.zsretail.erp.spi.ErpConnector;

/**
 * Code cleanup step 3: the part of an ErpConnector that only hands the catalogue to the engine, written once. The four
 * catalogue fetches run the {@link CatalogueEngine}: families and sub-families hand their changes to the import (the
 * job), the items and barcode runs apply theirs themselves and hand nothing. The summary of each run is the response
 * written to the communications log ({@link #getLastPullOperationResult()}), never the list. A connector extends it and
 * gives the rest (its other fetches, pushes, invoices).
 */
public abstract class CatalogueEngineConnector implements ErpConnector {

	/** The summary of the last fetch of this thread, for the communications log (like the Dynamics NAV connector). */
	private static final ThreadLocal<PullOperationResult<?>> LAST_PULL = new ThreadLocal<>();

	private final CatalogueEngine engine;

	protected CatalogueEngineConnector(CatalogueEngine engine) {
		this.engine = engine;
	}

	@Override
	public List<ErpItemFamilyDTO> fetchItemFamilies(ErpSyncFilter filter) {
		return changes(engine::families);
	}

	@Override
	public List<ErpItemSubFamilyDTO> fetchItemSubFamilies(ErpSyncFilter filter) {
		return changes(engine::subFamilies);
	}

	/** The items run applies its changes itself, in packets ({@link CatalogueEngine#items}): nothing left for the job. */
	@Override
	public List<ErpItemDTO> fetchItems(ErpSyncFilter filter) {
		changes(engine::items);
		return Collections.emptyList();
	}

	/** The barcode run applies its changes itself, until it has caught up ({@link CatalogueEngine#barcodes}). */
	@Override
	public List<ErpItemBarcodeDTO> fetchItemBarcodes(ErpSyncFilter filter) {
		changes(engine::barcodes);
		return Collections.emptyList();
	}

	/** One run: the rows handed to the import; its summary kept for the communications log. */
	protected final <T> List<T> changes(Supplier<CatalogueRun<T>> run) {
		LAST_PULL.remove();
		CatalogueRun<T> result = run.get();
		LAST_PULL.set(new PullOperationResult<>(result.getHanded(), result.getUrl(), result.getSummary()));
		return result.getHanded();
	}

	@Override
	public PullOperationResult<?> getLastPullOperationResult() {
		return LAST_PULL.get();
	}

	@Override
	public void clearLastPullOperationResult() {
		LAST_PULL.remove();
	}
}
