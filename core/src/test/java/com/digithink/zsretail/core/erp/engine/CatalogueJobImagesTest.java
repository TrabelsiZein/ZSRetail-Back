package com.digithink.zsretail.core.erp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.erp.api.CatalogueSettings;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * 3.1.1 (item images): the images part of the job SYNC_CATALOGUE ({@link CatalogueTestBed}, two variants of 70061 in
 * both points): last, after the barcodes; given the NAV item numbers the items part handed; its summary in the
 * communications log's row; a failure is a warning (the parts before it saved, the images' checkpoint unmoved), never
 * an error; not configured: skipped, no warning; no images part: the run as before.
 */
class CatalogueJobImagesTest {

	static final OffsetDateTime T1 = OffsetDateTime.parse("2026-10-11T16:53:37.137Z");
	static final String FRONT = "https://images.zen.com.tn/uploads/front.JPG";

	private CatalogueTestBed bed;
	private final ItemImagesEngineTest.Source source = new ItemImagesEngineTest.Source();
	private final ItemImagesEngineTest.HeadOffice headOffice = new ItemImagesEngineTest.HeadOffice();
	private final CatalogueTestBed.State imageState = new CatalogueTestBed.State();
	private ItemImagesEngine images;

	@BeforeEach
	void setUp() {
		bed = new CatalogueTestBed();
		bed.changesOnly = true;
		bed.items.put(CatalogueTestBed.ARGENTE, new ArrayList<>(Arrays.asList(row("70061~001"), row("70061~002"))));
		bed.items.put(CatalogueTestBed.FRANCHISE, new ArrayList<>(Arrays.asList(row("70061~001"))));
		headOffice.navItemNos.add("70061");
		source.images.put("70061", Collections.singletonList(FRONT));
		CatalogueSettings settings = bed.engine.getSettings();
		images = new ItemImagesEngine(source, headOffice, imageState, settings, delay -> {
		}, () -> OffsetDateTime.parse("2026-10-28T15:00:00Z"));
	}

	private static ErpItemDTO row(String code) {
		ErpItemDTO row = new ErpItemDTO();
		row.setExternalId(code);
		row.setCode(code);
		row.setName("CHEMISE");
		row.setUnitPrice(new BigDecimal("50"));
		row.setDefaultVAT(19);
		row.setActive(Boolean.TRUE);
		row.setNavItemNo("70061");
		row.setVariantCode(code.substring(code.indexOf('~') + 1));
		row.setLastModifiedAt(T1);
		return row;
	}

	@SuppressWarnings("unchecked")
	private List<String> partNames() {
		Map<String, Object> summary = (Map<String, Object>) bed.logged.get(bed.logged.size() - 1)[2];
		return new ArrayList<>(((Map<String, Object>) summary.get("parts")).keySet());
	}

	@Test
	@DisplayName("Images last, after the barcodes: the first run is full (every NAV item number); a success, its summary logged")
	void last() {
		bed.job(images).syncCatalogue();
		assertEquals(ErpCommunicationStatus.SUCCESS, bed.logged.get(0)[0]);
		List<String> parts = partNames();
		assertEquals("images", parts.get(parts.size() - 1));
		assertTrue(parts.indexOf("barcodes") < parts.indexOf("images"));
		assertEquals("full", bed.part("images").get("mode"));
		assertEquals(1, bed.part("images").get("itemsChanged"));
		assertEquals(Collections.singletonList(FRONT), headOffice.written.get("70061"));
	}

	@Test
	@DisplayName("Next run within 24 h: only the NAV item numbers of the items the items part handed are asked")
	void changedAsked() {
		bed.job(images).syncCatalogue();
		source.asked.clear();
		bed.items.get(CatalogueTestBed.ARGENTE).get(1).setName("CHEMISE MANCHES LONGUES");
		bed.items.get(CatalogueTestBed.ARGENTE).get(1).setLastModifiedAt(T1.plusDays(1));
		bed.job(images).syncCatalogue();
		assertEquals(Collections.singletonList(Collections.singletonList("70061")), source.asked);
		assertEquals("changes", bed.part("images").get("mode"));
		assertEquals(Collections.singleton("70061"), bed.engine.changedNavItemNos());
	}

	@Test
	@DisplayName("Images failing: a warning naming it, the catalogue saved, the checkpoint not written")
	void failureIsAWarning() {
		source.failure = new IllegalStateException("zen: reading the products API failed: HTTP 401 Unauthorized");
		source.failuresLeft = 1;
		ErpSyncWarningException warning = assertThrows(ErpSyncWarningException.class,
				() -> bed.job(images).syncCatalogue());
		assertTrue(warning.getMessage().startsWith("Images failed (the catalogue is saved, the next run asks again): zen:"),
				warning.getMessage());
		assertEquals(ErpCommunicationStatus.WARNING, bed.logged.get(0)[0]);
		assertNotNull(bed.part("images").get("error"));
		assertEquals(2, bed.part("items").get("new"), "the items part saved");
		assertNull(imageState.get(ItemImagesEngine.IMAGES_SINCE));
	}

	@Test
	@DisplayName("Not configured: the part says skipped, no warning; no images part: as before")
	void notConfigured() {
		source.configured = false;
		bed.job(images).syncCatalogue();
		assertEquals(ErpCommunicationStatus.SUCCESS, bed.logged.get(0)[0]);
		assertEquals(ItemImagesEngine.NOT_CONFIGURED, bed.part("images").get("skipped"));

		bed.job().syncCatalogue();
		assertTrue(!partNames().contains("images"));
	}
}
