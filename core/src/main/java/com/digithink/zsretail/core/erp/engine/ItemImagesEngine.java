package com.digithink.zsretail.core.erp.engine;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnErpAbility;
import com.digithink.zsretail.core.model.ItemImage;
import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.CatalogueSettings;
import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.ErpCatalogueSource;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.erp.api.dto.ErpItemImagesDTO;

/**
 * 3.1.1 (item images): the images part of the catalogue job (CatalogueJob, last), on a connector that reads them
 * ({@link ErpCatalogueSource#readsItemImages()}; otherwise the part is skipped, "not configured").
 * <ul>
 * <li><b>Which items.</b> The first run (no checkpoint) asks every NAV item number of the head office; the next ones ask
 * the NAV item numbers of the items the items part just changed, then the items changed in the ERP from the checkpoint
 * (less {@link #OVERLAP}, the two clocks) to the run's start.</li>
 * <li><b>Calls.</b> {@link ErpCatalogueSource#ITEM_IMAGES_PER_CALL} numbers per call, each call's answer applied before
 * the next call ({@link HeadOfficeImages#apply}): the variants of an item answered get its images as URL rows (the
 * first main; none: their URL rows removed), FILE rows untouched; an item not answered keeps its rows. An address
 * longer than item_image.path is left out (counted).</li>
 * <li><b>Checkpoint.</b> images.since in the state table: the run's start, written once every call succeeded, never in a
 * dry run (which compares and counts only). A failed call fails the part: what was applied stays, the checkpoint does
 * not move, the next run asks again.</li>
 * </ul>
 */
@Component
@ConditionalOnErpAbility(ErpAbility.HEAD_OFFICE_STOCK_POINTS)
public class ItemImagesEngine {

	static final String IMAGES_SINCE = "images.since";
	/** The delta read starts this long before the checkpoint: the ERP's clock and ours may differ. */
	static final Duration OVERLAP = Duration.ofMinutes(5);

	public static final String NOT_CONFIGURED = "not configured";
	static final String ALREADY_RUNNING = "an images run is already running: this one does not start";

	private static final Logger LOG = LogManager.getLogger(ItemImagesEngine.class);

	private final ErpCatalogueSource source;
	private final HeadOfficeImages headOffice;
	private final CatalogueState state;
	private final CatalogueSettings settings;
	private final Supplier<OffsetDateTime> clock;
	private final AtomicBoolean running = new AtomicBoolean();

	@Autowired
	public ItemImagesEngine(ErpCatalogueSource source, HeadOfficeImages headOffice, CatalogueState state,
			CatalogueSettings settings) {
		this(source, headOffice, state, settings, RetryingCatalogueSource.SLEEP,
				() -> OffsetDateTime.now(ZoneOffset.UTC));
	}

	/** With a given pause before a retry and a given clock: used by the tests. */
	ItemImagesEngine(ErpCatalogueSource source, HeadOfficeImages headOffice, CatalogueState state,
			CatalogueSettings settings, RetryingCatalogueSource.Pause pause, Supplier<OffsetDateTime> clock) {
		this.source = new RetryingCatalogueSource(source, pause);
		this.headOffice = headOffice;
		this.state = state;
		this.settings = settings;
		this.clock = clock;
	}

	/** True when the connector reads images: otherwise the job skips the part. */
	public boolean configured() {
		return source.readsItemImages();
	}

	/** The checkpoint, or null (none, or not readable): the next run asks every item. */
	public OffsetDateTime checkpoint() {
		String value = state.get(IMAGES_SINCE);
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value.trim());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/**
	 * One images run; changedNavItemNos: the NAV item numbers of the items the items part changed in this run (asked
	 * after a first run). Summary: mode (first / changes), since, asked, calls, answered, itemsChanged, itemsEqual,
	 * itemsCleared, notAnswered, unknown, urlsTooLong, durationMs.
	 */
	public CatalogueRun<ErpItemImagesDTO> run(Collection<String> changedNavItemNos) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("source", source.itemImagesLabel());
		summary.put("dryRun", settings.dryRun());
		if (!configured()) {
			summary.put("skipped", NOT_CONFIGURED);
			return new CatalogueRun<>(new ArrayList<>(), summary, null);
		}
		if (!running.compareAndSet(false, true)) {
			throw new ErpSyncWarningException(ALREADY_RUNNING);
		}
		try {
			return read(changedNavItemNos, summary);
		} finally {
			running.set(false);
		}
	}

	private CatalogueRun<ErpItemImagesDTO> read(Collection<String> changedNavItemNos, Map<String, Object> summary) {
		long started = System.nanoTime();
		OffsetDateTime since = checkpoint();
		OffsetDateTime start = clock.get();
		boolean write = !settings.dryRun();
		List<String> asked = new ArrayList<>();
		if (since == null) {
			asked.addAll(headOffice.navItemNos());
		} else if (changedNavItemNos != null) {
			changedNavItemNos.stream().filter(no -> no != null && !no.trim().isEmpty()).map(String::trim)
					.collect(Collectors.toCollection(TreeSet::new)).forEach(asked::add);
		}
		summary.put("mode", since == null ? "first" : "changes");
		summary.put("since", since == null ? null : since.toString());
		summary.put("asked", asked.size());

		HeadOfficeImages.Applied applied = new HeadOfficeImages.Applied();
		Set<String> answered = new TreeSet<>();
		int[] tooLong = { 0 };
		int calls = 0;
		int size = ErpCatalogueSource.ITEM_IMAGES_PER_CALL;
		for (int from = 0; from < asked.size(); from += size) {
			List<String> batch = asked.subList(from, Math.min(from + size, asked.size()));
			List<ErpItemImagesDTO> answer = source.itemImages(new ArrayList<>(batch));
			calls++;
			applied.add(headOffice.apply(byNavItemNo(answer, answered, tooLong), write));
		}
		Set<String> askedSet = new TreeSet<>(asked);
		int notAnswered = (int) askedSet.stream().filter(no -> !answered.contains(no)).count();
		int changedInErp = 0;
		if (since != null) {
			List<ErpItemImagesDTO> answer = source.itemImagesChanged(since.minus(OVERLAP), start);
			calls++;
			Set<String> changed = new TreeSet<>();
			Map<String, List<String>> urls = byNavItemNo(answer, changed, tooLong);
			changedInErp = changed.size();
			answered.addAll(changed);
			applied.add(headOffice.apply(urls, write));
		}
		if (write) {
			state.put(IMAGES_SINCE, start.toString());
		}
		summary.put("changedInErp", changedInErp);
		summary.put("calls", calls);
		summary.put("answered", answered.size());
		summary.put("itemsChanged", applied.itemsChanged);
		summary.put("itemsEqual", applied.itemsEqual);
		summary.put("itemsCleared", applied.itemsCleared);
		summary.put("notAnswered", notAnswered);
		summary.put("unknown", applied.unknown.size());
		if (tooLong[0] > 0) {
			summary.put("urlsTooLong", tooLong[0]);
		}
		summary.put("durationMs", (System.nanoTime() - started) / 1_000_000);
		LOG.info("ERP item images ({}): {} asked in {} calls, {} answered, {} variants changed ({} cleared), {} equal,"
				+ " {} not answered, {} unknown{}", summary.get("mode"), asked.size(), calls, answered.size(),
				applied.itemsChanged, applied.itemsCleared, applied.itemsEqual, notAnswered, applied.unknown.size(),
				write ? "" : " (dry run: nothing written)");
		return new CatalogueRun<>(new ArrayList<>(), summary, null);
	}

	/**
	 * The answer by NAV item number (trimmed; a later one of the same number wins), its addresses trimmed, blank ones
	 * and repeats left out, those longer than item_image.path left out and counted; the numbers added to answered.
	 */
	static Map<String, List<String>> byNavItemNo(List<ErpItemImagesDTO> answer, Set<String> answered, int[] tooLong) {
		Map<String, List<String>> urls = new LinkedHashMap<>();
		if (answer == null) {
			return urls;
		}
		for (ErpItemImagesDTO item : answer) {
			String no = item == null || item.getNavItemNo() == null ? "" : item.getNavItemNo().trim();
			if (no.isEmpty()) {
				continue;
			}
			List<String> kept = new ArrayList<>();
			for (String url : item.getUrls() == null ? new ArrayList<String>() : item.getUrls()) {
				String value = url == null ? "" : url.trim();
				if (value.isEmpty() || kept.contains(value)) {
					continue;
				}
				if (value.length() > ItemImage.PATH_LENGTH) {
					tooLong[0]++;
					continue;
				}
				kept.add(value);
			}
			urls.put(no, kept);
			answered.add(no);
		}
		return urls;
	}
}
