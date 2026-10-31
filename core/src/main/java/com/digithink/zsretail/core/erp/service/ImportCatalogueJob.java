package com.digithink.zsretail.core.erp.service;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpSyncFilter;

import lombok.RequiredArgsConstructor;

/**
 * 3.1.1: the job IMPORT_CATALOGUE, the catalogue of an installation whose catalogue comes from the ERP through the
 * ErpConnector's fetches (a store on the ERP, a head office whose ERP owns everything), in one run and in a fixed order:
 * catégories, families, sub-families, sections, items, barcodes. Each part is the read (the communications log), the
 * import and the checkpoint of the job it replaced (IMPORT_ITEM_FAMILIES, IMPORT_ITEM_SUBFAMILIES, IMPORT_ITEMS,
 * IMPORT_ITEM_BARCODES; their General Setup checkpoints unchanged); the catégories and the sections, new, have no
 * checkpoint and are read whole (a connector without them answers none). The first failing part stops the run: the
 * parts before it are saved, the next run goes on, and barcodes never come before their items. Not the head office's
 * SYNC_CATALOGUE (the catalogue engine, by points de stock).
 */
@Service
@RequiredArgsConstructor
public class ImportCatalogueJob {

	private static final Logger LOGGER = LoggerFactory.getLogger(ImportCatalogueJob.class);

	/** The parts, in the order of a run, with their label in the messages. */
	enum Part {
		CATEGORIES("Catégories"), FAMILIES("Families"), SUB_FAMILIES("Sub-families"), SECTIONS("Sections"),
		ITEMS("Items"), BARCODES("Barcodes");

		final String label;

		Part(String label) {
			this.label = label;
		}
	}

	private final ErpSynchronizationManager synchronizationManager;
	private final ErpItemBootstrapService bootstrap;
	private final ErpSyncCheckpointService checkpoints;

	/** One run: every part in order; the first that fails stops it, its message naming the part. */
	public void run() {
		StringBuilder read = new StringBuilder();
		part(read, Part.CATEGORIES, () -> {
			List<ErpItemCategoryDTO> rows = synchronizationManager.pullItemCategories(new ErpSyncFilter());
			bootstrap.importItemCategories(rows);
			return size(rows);
		});
		part(read, Part.FAMILIES, () -> {
			ErpSyncOperation part = ErpSyncOperation.IMPORT_ITEM_FAMILIES;
			List<ErpItemFamilyDTO> rows = synchronizationManager
					.pullItemFamilies(checkpoints.createFilterForCataloguePart(part));
			bootstrap.importItemFamilies(rows);
			checkpoints.updateLastSyncForCataloguePart(part, rows);
			return size(rows);
		});
		part(read, Part.SUB_FAMILIES, () -> {
			ErpSyncOperation part = ErpSyncOperation.IMPORT_ITEM_SUBFAMILIES;
			List<ErpItemSubFamilyDTO> rows = synchronizationManager
					.pullItemSubFamilies(checkpoints.createFilterForCataloguePart(part));
			bootstrap.importItemSubFamilies(rows);
			checkpoints.updateLastSyncForCataloguePart(part, rows);
			return size(rows);
		});
		part(read, Part.SECTIONS, () -> {
			List<ErpItemSectionDTO> rows = synchronizationManager.pullItemSections(new ErpSyncFilter());
			bootstrap.importItemSections(rows);
			return size(rows);
		});
		part(read, Part.ITEMS, () -> {
			ErpSyncOperation part = ErpSyncOperation.IMPORT_ITEMS;
			List<ErpItemDTO> rows = synchronizationManager.pullItems(checkpoints.createFilterForCataloguePart(part));
			bootstrap.importItems(rows);
			checkpoints.updateLastSyncForCataloguePart(part, rows);
			return size(rows);
		});
		part(read, Part.BARCODES, () -> {
			ErpSyncOperation part = ErpSyncOperation.IMPORT_ITEM_BARCODES;
			List<ErpItemBarcodeDTO> rows = synchronizationManager
					.pullItemBarcodes(checkpoints.createFilterForCataloguePart(part));
			bootstrap.importItemBarcodes(rows);
			checkpoints.updateLastSyncForCataloguePart(part, rows);
			return size(rows);
		});
		LOGGER.info("ERP catalogue import, rows read: {}", read);
	}

	/**
	 * One part, its rows read added to the run's line; a failure stops the run, its message naming the part and the parts
	 * that wait. A warning (e.g. no DEFAULT_LOCATION) stays a warning for the scheduler.
	 */
	static void part(StringBuilder read, Part part, Supplier<Integer> body) {
		int rows;
		try {
			rows = body.get();
		} catch (ErpSyncWarningException warning) {
			throw new ErpSyncWarningException(stopped(part) + warning.getMessage());
		} catch (RuntimeException e) {
			throw new IllegalStateException(stopped(part) + e.getMessage(), e);
		}
		read.append(read.length() == 0 ? "" : ", ").append(part.label.toLowerCase()).append(' ').append(rows);
	}

	/** e.g. "Items failed (the parts before it are saved; barcodes wait for the next run): " */
	static String stopped(Part part) {
		StringBuilder waiting = new StringBuilder();
		for (Part later : Part.values()) {
			if (later.ordinal() > part.ordinal()) {
				waiting.append(waiting.length() == 0 ? "" : ", ").append(later.label.toLowerCase());
			}
		}
		return part.label + " failed (the parts before it are saved; "
				+ (waiting.length() == 0 ? "the next run reads them again" : waiting + " wait for the next run") + "): ";
	}

	private static int size(List<?> rows) {
		return rows == null ? 0 : rows.size();
	}
}
