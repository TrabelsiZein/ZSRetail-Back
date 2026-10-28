package com.digithink.zsretail.core.erp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.ItemImage;
import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.CatalogueSettings;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCatalogueSource;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemImagesDTO;

/**
 * 3.1.1 (item images): the images part of the catalogue job on a source and head office in memory: the first run asks
 * every NAV item number in calls of 100, the next ones the items changed by the items part then the ERP's changes from
 * the checkpoint (less 5 minutes) to the run's start; the checkpoint written once every call succeeded, never in a dry
 * run; a failed call leaves it; an I/O failure is read again once; not configured: skipped; the answer cleaned.
 */
class ItemImagesEngineTest {

	static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-28T15:00:00Z");
	static final String FRONT = "https://images.zen.com.tn/uploads/front.JPG";
	static final String DETAILS = "https://images.zen.com.tn/uploads/details.JPG";

	/** The ERP's images, its calls and its failures. */
	static final class Source implements ErpCatalogueSource {
		boolean configured = true;
		final Map<String, List<String>> images = new TreeMap<>();
		final List<List<String>> asked = new ArrayList<>();
		final List<String> windows = new ArrayList<>();
		final List<ErpItemImagesDTO> changed = new ArrayList<>();
		RuntimeException failure;
		int failuresLeft;

		@Override
		public boolean readsItemImages() {
			return configured;
		}

		@Override
		public List<ErpItemImagesDTO> itemImages(List<String> navItemNos) {
			fail();
			asked.add(new ArrayList<>(navItemNos));
			return navItemNos.stream().filter(images::containsKey)
					.map(no -> new ErpItemImagesDTO(no, images.get(no))).collect(Collectors.toList());
		}

		@Override
		public List<ErpItemImagesDTO> itemImagesChanged(OffsetDateTime from, OffsetDateTime to) {
			fail();
			windows.add(from + " " + to);
			return changed;
		}

		private void fail() {
			if (failure != null && failuresLeft-- > 0) {
				throw failure;
			}
		}

		@Override
		public String itemImagesLabel() {
			return "products API";
		}

		@Override
		public ErpCategories categories() {
			throw new UnsupportedOperationException();
		}

		@Override
		public ErpRead<ErpItemDTO> items(String locationCode) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ErpRead<ErpBarcodeRow> barcodesAfter(long position) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ErpRead<ErpBarcodeRow> barcodesOfItems(List<String> itemCodes) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String pageLabel(Part part) {
			return part.name();
		}

		@Override
		public String pageUrl(Part part) {
			return null;
		}
	}

	/** The head office: its NAV item numbers and what apply received. */
	static final class HeadOffice implements HeadOfficeImages {
		final List<String> navItemNos = new ArrayList<>();
		final Map<String, List<String>> written = new LinkedHashMap<>();
		final List<Boolean> writes = new ArrayList<>();

		@Override
		public List<String> navItemNos() {
			return navItemNos;
		}

		@Override
		public Applied apply(Map<String, List<String>> urlsByNavItemNo, boolean write) {
			writes.add(write);
			Applied applied = new Applied();
			urlsByNavItemNo.forEach((no, urls) -> {
				if (!navItemNos.contains(no)) {
					applied.unknown.add(no);
					return;
				}
				if (urls.equals(written.get(no))) {
					applied.itemsEqual++;
				} else {
					applied.itemsChanged++;
					if (urls.isEmpty()) {
						applied.itemsCleared++;
					}
					if (write) {
						written.put(no, urls);
					}
				}
			});
			return applied;
		}
	}

	private final Source source = new Source();
	private final HeadOffice headOffice = new HeadOffice();
	private final CatalogueTestBed.State state = new CatalogueTestBed.State();
	private boolean dryRun;
	private final List<String> pauses = new ArrayList<>();

	private ItemImagesEngine engine() {
		CatalogueSettings settings = new CatalogueSettings() {
			@Override
			public int packetSize() {
				return 500;
			}

			@Override
			public boolean dryRun() {
				return dryRun;
			}

			@Override
			public Integer defaultVat() {
				return 19;
			}
		};
		return new ItemImagesEngine(source, headOffice, state, settings, delay -> pauses.add(delay.toString()),
				() -> NOW);
	}

	private static List<String> numbers(int count) {
		return IntStream.rangeClosed(1, count).mapToObj(i -> String.format("N%04d", i)).collect(Collectors.toList());
	}

	@Test
	@DisplayName("First run: every NAV item number in calls of 100, each answer applied; the checkpoint is the run's start")
	void firstRun() {
		headOffice.navItemNos.addAll(numbers(250));
		source.images.put("N0001", Arrays.asList(FRONT, DETAILS));
		source.images.put("N0150", Collections.singletonList(FRONT));
		CatalogueRun<ErpItemImagesDTO> run = engine().run(Collections.singleton("N0001"));
		assertEquals(Arrays.asList(100, 100, 50), source.asked.stream().map(List::size).collect(Collectors.toList()));
		assertTrue(source.windows.isEmpty(), "no delta on the first run");
		assertEquals(Arrays.asList(FRONT, DETAILS), headOffice.written.get("N0001"));
		assertEquals("first", run.getSummary().get("mode"));
		assertEquals(250, run.getSummary().get("asked"));
		assertEquals(3, run.getSummary().get("calls"));
		assertEquals(2, run.getSummary().get("answered"));
		assertEquals(2, run.getSummary().get("itemsChanged"));
		assertEquals(248, run.getSummary().get("notAnswered"));
		assertEquals(NOW.toString(), state.get(ItemImagesEngine.IMAGES_SINCE));
	}

	@Test
	@DisplayName("Next run: the items changed by the items part, then the ERP's changes from the checkpoint less 5 minutes")
	void changesRun() {
		headOffice.navItemNos.addAll(Arrays.asList("A", "B", "C"));
		state.put(ItemImagesEngine.IMAGES_SINCE, "2026-10-28T12:00Z");
		source.images.put("B", Collections.singletonList(FRONT));
		source.changed.add(new ErpItemImagesDTO("C", Arrays.asList(DETAILS)));
		source.changed.add(new ErpItemImagesDTO("ZZ", Arrays.asList(DETAILS)));
		CatalogueRun<ErpItemImagesDTO> run = engine().run(Arrays.asList(" B ", "A", "", null));
		assertEquals(Collections.singletonList(Arrays.asList("A", "B")), source.asked);
		assertEquals(Collections.singletonList("2026-10-28T11:55Z " + NOW), source.windows);
		assertEquals("changes", run.getSummary().get("mode"));
		assertEquals(2, run.getSummary().get("changedInErp"));
		assertEquals(1, run.getSummary().get("notAnswered"), "A asked, not answered: its rows kept");
		assertEquals(1, run.getSummary().get("unknown"), "ZZ: no item at the head office");
		assertEquals(Collections.singletonList(DETAILS), headOffice.written.get("C"));
		assertEquals(NOW.toString(), state.get(ItemImagesEngine.IMAGES_SINCE));
	}

	@Test
	@DisplayName("Nothing changed by the items part: no call by number, only the ERP's changes")
	void onlyTheDelta() {
		state.put(ItemImagesEngine.IMAGES_SINCE, "2026-10-28T12:00Z");
		engine().run(Collections.emptySet());
		assertTrue(source.asked.isEmpty());
		assertEquals(1, source.windows.size());
	}

	@Test
	@DisplayName("A failed call fails the run: what was applied stays, the checkpoint does not move")
	void failure() {
		headOffice.navItemNos.addAll(numbers(150));
		source.images.put("N0001", Collections.singletonList(FRONT));
		ItemImagesEngine engine = engine();
		engine.run(Collections.emptySet()); // first run: checkpoint NOW
		state.put(ItemImagesEngine.IMAGES_SINCE, "2026-10-28T12:00Z");
		source.failure = new IllegalStateException("zen: reading the products API failed: HTTP 401");
		source.failuresLeft = 1;
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> engine.run(Collections.singleton("N0002")));
		assertTrue(e.getMessage().contains("HTTP 401"));
		assertEquals("2026-10-28T12:00Z", state.get(ItemImagesEngine.IMAGES_SINCE));
		assertTrue(pauses.isEmpty(), "not an I/O failure: not read again");
	}

	@Test
	@DisplayName("An I/O failure (timeout) is read again once, after the pause")
	void retried() {
		headOffice.navItemNos.add("A");
		source.images.put("A", Collections.singletonList(FRONT));
		source.failure = new IllegalStateException("zen: no answer", new IOException("Read timed out"));
		source.failuresLeft = 1;
		engine().run(Collections.emptySet());
		assertEquals(1, pauses.size());
		assertEquals(Collections.singletonList(FRONT), headOffice.written.get("A"));
	}

	@Test
	@DisplayName("A dry run reads and compares: nothing written, no checkpoint")
	void dryRun() {
		dryRun = true;
		headOffice.navItemNos.add("A");
		source.images.put("A", Collections.singletonList(FRONT));
		CatalogueRun<ErpItemImagesDTO> run = engine().run(Collections.emptySet());
		assertEquals(Collections.singletonList(false), headOffice.writes);
		assertEquals(1, run.getSummary().get("itemsChanged"));
		assertNull(state.get(ItemImagesEngine.IMAGES_SINCE));
	}

	@Test
	@DisplayName("Not configured: skipped, no call, no checkpoint")
	void notConfigured() {
		source.configured = false;
		headOffice.navItemNos.add("A");
		CatalogueRun<ErpItemImagesDTO> run = engine().run(Collections.emptySet());
		assertEquals(ItemImagesEngine.NOT_CONFIGURED, run.getSummary().get("skipped"));
		assertTrue(source.asked.isEmpty());
		assertNull(state.get(ItemImagesEngine.IMAGES_SINCE));
	}

	@Test
	@DisplayName("The answer cleaned: numbers and addresses trimmed, blanks and repeats left out, too long counted")
	void cleaned() {
		String tooLong = "https://x/" + String.join("", Collections.nCopies(ItemImage.PATH_LENGTH, "a"));
		java.util.Set<String> answered = new java.util.TreeSet<>();
		int[] count = { 0 };
		Map<String, List<String>> urls = ItemImagesEngine.byNavItemNo(Arrays.asList(
				new ErpItemImagesDTO(" A ", Arrays.asList(" " + FRONT + " ", "", FRONT, null, tooLong, DETAILS)),
				new ErpItemImagesDTO("", Collections.singletonList(FRONT)), null,
				new ErpItemImagesDTO("B", null)), answered, count);
		assertEquals(Arrays.asList(FRONT, DETAILS), urls.get("A"));
		assertEquals(Collections.emptyList(), urls.get("B"), "no image: an empty list (its rows removed)");
		assertEquals(2, urls.size());
		assertEquals(1, count[0]);
	}
}
