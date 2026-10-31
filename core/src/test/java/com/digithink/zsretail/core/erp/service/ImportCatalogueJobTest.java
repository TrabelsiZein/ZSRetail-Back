package com.digithink.zsretail.core.erp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.digithink.zsretail.core.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpSyncFilter;

/**
 * 3.1.1, the job IMPORT_CATALOGUE: the six parts in order (read, import, then the checkpoint of the four parts that have
 * one), each part's filter from its own checkpoint; the first failing part stops the run, its message naming it and the
 * parts that wait, a warning staying a warning; barcodes never read before the items.
 */
class ImportCatalogueJobTest {

	private ErpSynchronizationManager manager;
	private ErpItemBootstrapService bootstrap;
	private ErpSyncCheckpointService checkpoints;
	private ImportCatalogueJob job;

	private final List<ErpItemCategoryDTO> categories = Arrays.asList(new ErpItemCategoryDTO());
	private final List<ErpItemFamilyDTO> families = Arrays.asList(new ErpItemFamilyDTO());
	private final List<ErpItemSubFamilyDTO> subFamilies = Arrays.asList(new ErpItemSubFamilyDTO());
	private final List<ErpItemSectionDTO> sections = Collections.emptyList();
	private final List<ErpItemDTO> items = Arrays.asList(new ErpItemDTO(), new ErpItemDTO());
	private final List<ErpItemBarcodeDTO> barcodes = Arrays.asList(new ErpItemBarcodeDTO());

	private final ErpSyncFilter familiesFilter = new ErpSyncFilter();
	private final ErpSyncFilter subFamiliesFilter = new ErpSyncFilter();
	private final ErpSyncFilter itemsFilter = new ErpSyncFilter();
	private final ErpSyncFilter barcodesFilter = new ErpSyncFilter();

	@BeforeEach
	void setUp() {
		manager = mock(ErpSynchronizationManager.class);
		bootstrap = mock(ErpItemBootstrapService.class);
		checkpoints = mock(ErpSyncCheckpointService.class);
		job = new ImportCatalogueJob(manager, bootstrap, checkpoints);
		when(checkpoints.createFilterForCataloguePart(ErpSyncOperation.IMPORT_ITEM_FAMILIES)).thenReturn(familiesFilter);
		when(checkpoints.createFilterForCataloguePart(ErpSyncOperation.IMPORT_ITEM_SUBFAMILIES))
				.thenReturn(subFamiliesFilter);
		when(checkpoints.createFilterForCataloguePart(ErpSyncOperation.IMPORT_ITEMS)).thenReturn(itemsFilter);
		when(checkpoints.createFilterForCataloguePart(ErpSyncOperation.IMPORT_ITEM_BARCODES)).thenReturn(barcodesFilter);
		when(manager.pullItemCategories(any())).thenReturn(categories);
		when(manager.pullItemFamilies(familiesFilter)).thenReturn(families);
		when(manager.pullItemSubFamilies(subFamiliesFilter)).thenReturn(subFamilies);
		when(manager.pullItemSections(any())).thenReturn(sections);
		when(manager.pullItems(itemsFilter)).thenReturn(items);
		when(manager.pullItemBarcodes(barcodesFilter)).thenReturn(barcodes);
	}

	@Test
	@DisplayName("The six parts in order: catégories, families, sub-families, sections, items, barcodes; each its checkpoint")
	void order() {
		job.run();
		InOrder order = inOrder(manager, bootstrap, checkpoints);
		order.verify(manager).pullItemCategories(any());
		order.verify(bootstrap).importItemCategories(categories);
		order.verify(manager).pullItemFamilies(familiesFilter);
		order.verify(bootstrap).importItemFamilies(families);
		order.verify(checkpoints).updateLastSyncForCataloguePart(ErpSyncOperation.IMPORT_ITEM_FAMILIES, families);
		order.verify(manager).pullItemSubFamilies(subFamiliesFilter);
		order.verify(bootstrap).importItemSubFamilies(subFamilies);
		order.verify(checkpoints).updateLastSyncForCataloguePart(ErpSyncOperation.IMPORT_ITEM_SUBFAMILIES, subFamilies);
		order.verify(manager).pullItemSections(any());
		order.verify(bootstrap).importItemSections(sections);
		order.verify(manager).pullItems(itemsFilter);
		order.verify(bootstrap).importItems(items);
		order.verify(checkpoints).updateLastSyncForCataloguePart(ErpSyncOperation.IMPORT_ITEMS, items);
		order.verify(manager).pullItemBarcodes(barcodesFilter);
		order.verify(bootstrap).importItemBarcodes(barcodes);
		order.verify(checkpoints).updateLastSyncForCataloguePart(ErpSyncOperation.IMPORT_ITEM_BARCODES, barcodes);
		order.verifyNoMoreInteractions();
	}

	@Test
	@DisplayName("Items failing: the run stops, barcodes not read, the items' checkpoint kept, the message names the part")
	void stopsAtItems() {
		RuntimeException nav = new RuntimeException("HTTP 500 StockkeepingUnitList");
		when(manager.pullItems(itemsFilter)).thenThrow(nav);
		IllegalStateException failed = assertThrows(IllegalStateException.class, job::run);
		assertEquals("Items failed (the parts before it are saved; barcodes wait for the next run): HTTP 500"
				+ " StockkeepingUnitList", failed.getMessage());
		assertSame(nav, failed.getCause());
		verify(bootstrap).importItemSubFamilies(subFamilies);
		verify(bootstrap, never()).importItems(any());
		verify(checkpoints, never()).updateLastSyncForCataloguePart(ErpSyncOperation.IMPORT_ITEMS, items);
		verify(manager, never()).pullItemBarcodes(any());
		verify(bootstrap, never()).importItemBarcodes(any());
	}

	@Test
	@DisplayName("Families failing (the read or the import): nothing after them, the catégories already saved")
	void stopsAtFamilies() {
		when(bootstrap.importItemFamilies(families)).thenThrow(new IllegalArgumentException("duplicate code"));
		IllegalStateException failed = assertThrows(IllegalStateException.class, job::run);
		assertEquals("Families failed (the parts before it are saved; sub-families, sections, items, barcodes wait for the"
				+ " next run): duplicate code", failed.getMessage());
		verify(bootstrap).importItemCategories(categories);
		verify(checkpoints, never()).updateLastSyncForCataloguePart(any(), any());
		verify(manager, never()).pullItemSubFamilies(any());
		verify(manager, never()).pullItems(any());
	}

	@Test
	@DisplayName("A warning (no DEFAULT_LOCATION) stays a warning for the scheduler, the run stopped at its part")
	void warningStaysWarning() {
		when(manager.pullItems(itemsFilter)).thenThrow(new ErpSyncWarningException("DEFAULT_LOCATION is not configured"));
		ErpSyncWarningException warning = assertThrows(ErpSyncWarningException.class, job::run);
		assertEquals("Items failed (the parts before it are saved; barcodes wait for the next run): DEFAULT_LOCATION is"
				+ " not configured", warning.getMessage());
		verify(manager, never()).pullItemBarcodes(any());
	}

	@Test
	@DisplayName("Barcodes failing, the last part: the message says the next run reads them again")
	void stopsAtBarcodes() {
		when(manager.pullItemBarcodes(barcodesFilter)).thenThrow(new RuntimeException("timeout"));
		IllegalStateException failed = assertThrows(IllegalStateException.class, job::run);
		assertEquals("Barcodes failed (the parts before it are saved; the next run reads them again): timeout",
				failed.getMessage());
		verify(checkpoints).updateLastSyncForCataloguePart(ErpSyncOperation.IMPORT_ITEMS, items);
		verify(checkpoints, never()).updateLastSyncForCataloguePart(ErpSyncOperation.IMPORT_ITEM_BARCODES, barcodes);
	}
}
