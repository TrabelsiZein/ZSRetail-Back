package com.digithink.zsretail.erp.navpospages.sync;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.service.ErpCommunicationService;
import com.digithink.zsretail.erp.service.ErpSyncWarningException;
import com.digithink.zsretail.erp.spi.ErpCatalogueSync;

/**
 * Release 2.2: the job SYNC_CATALOGUE ("Sync catalogue"), the whole catalogue from the ERP in one run, in this order:
 * every family, every sub-family, the items and the rows of every point de stock, then the barcodes (the barcode run,
 * until it has caught up, the barcodes of the new items included). Replaces the four catalogue jobs on a head office
 * whose catalogue comes from the ERP (HeadOfficeErpJobs).
 * <ul>
 * <li>No active point de stock: nothing is read, a warning ({@link NavPosPagesSync#NO_STOCK_POINT}).</li>
 * <li>A part that fails stops the run there: the parts before it stay saved, the next run goes on.</li>
 * <li>Nothing shows as a success when it did nothing: a part that waited, was refused (a run already running) or kept
 * something back (a guard), and a dry run, make the run a warning with the reasons ({@link ErpSyncWarningException}:
 * "Run now" shows the reason, the job's status is WARNING).</li>
 * <li>One row of the communications log per run (operation SYNC_CATALOGUE): each part with its counts and duration.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
public class NavPosPagesCatalogueJob implements ErpCatalogueSync {

	static final String FAMILIES = "families";
	static final String SUB_FAMILIES = "subFamilies";
	static final String ITEMS = "items";
	static final String BARCODES = "barcodes";

	public static final String DRY_RUN = "dry run: read and compared only, nothing written";

	/** The communications log: one row per run. */
	public interface CommunicationLog {
		void write(ErpCommunicationStatus status, Map<String, Object> summary, String message, LocalDateTime start,
				LocalDateTime end);
	}

	private final NavPosPagesSync sync;
	private final NavPosPagesImport importer;
	private final NavPosPagesStockPoints stockPoints;
	private final NavPosPagesProperties properties;
	private final CommunicationLog log;

	@Autowired
	public NavPosPagesCatalogueJob(NavPosPagesSync sync, NavPosPagesImport importer, NavPosPagesStockPoints stockPoints,
			NavPosPagesProperties properties, ErpCommunicationService communications) {
		this(sync, importer, stockPoints, properties, (status, summary, message, start, end) -> communications
				.logOperation(ErpSyncOperation.SYNC_CATALOGUE, null, summary, status, null, message, start, end));
	}

	/** With a given log: used by the tests. */
	public NavPosPagesCatalogueJob(NavPosPagesSync sync, NavPosPagesImport importer, NavPosPagesStockPoints stockPoints,
			NavPosPagesProperties properties, CommunicationLog log) {
		this.sync = sync;
		this.importer = importer;
		this.stockPoints = stockPoints;
		this.properties = properties;
		this.log = log;
	}

	@Override
	public void syncCatalogue() {
		LocalDateTime start = LocalDateTime.now();
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("run", "catalogue");
		summary.put("dryRun", properties.isDryRun());
		Map<String, Object> parts = new LinkedHashMap<>();
		summary.put("parts", parts);
		List<String> warnings = new ArrayList<>();
		String current = null;
		try {
			if (stockPoints.activePoints().isEmpty()) {
				warnings.add(NavPosPagesSync.NO_STOCK_POINT);
			} else {
				current = FAMILIES;
				boolean go = inRounds(parts, FAMILIES, "Families", sync::families,
						run -> importer.families(run.getHanded()), warnings);
				if (go) {
					current = SUB_FAMILIES;
					go = inRounds(parts, SUB_FAMILIES, "Sub-families", sync::subFamilies,
							run -> importer.subFamilies(run.getHanded()), warnings);
				}
				if (go) {
					current = ITEMS;
					go = once(parts, ITEMS, "Items", sync::items, warnings);
				}
				if (go) {
					current = BARCODES;
					once(parts, BARCODES, "Barcodes", sync::barcodes, warnings);
				}
				current = null;
				if (properties.isDryRun()) {
					warnings.add(DRY_RUN);
				}
			}
		} catch (RuntimeException e) {
			String message = (current == null ? "Sync catalogue" : label(current)) + " failed (the parts before it are"
					+ " saved, the next run goes on): " + e.getMessage();
			if (current != null) {
				Map<String, Object> failed = new LinkedHashMap<>();
				failed.put("error", e.getMessage());
				parts.put(current, failed);
			}
			summary.put("result", ErpCommunicationStatus.ERROR.name());
			summary.put("message", message);
			log.write(ErpCommunicationStatus.ERROR, summary, message, start, LocalDateTime.now());
			throw e;
		}
		ErpCommunicationStatus status = warnings.isEmpty() ? ErpCommunicationStatus.SUCCESS : ErpCommunicationStatus.WARNING;
		String message = warnings.isEmpty() ? null : String.join("; ", warnings);
		summary.put("result", status.name());
		if (message != null) {
			summary.put("message", message);
		}
		log.write(status, summary, message, start, LocalDateTime.now());
		if (message != null) {
			throw new ErpSyncWarningException(message);
		}
	}

	/**
	 * Families or sub-families: one packet per run of the sync, handed to the import, again while some are held back and
	 * the number still to hand goes down. The part's summary: the first round's counts (what was to do), the total handed,
	 * the rounds, the duration. Returns false (with a warning) when the part waited: the next parts would wait too.
	 */
	private <T> boolean inRounds(Map<String, Object> parts, String key, String label, Supplier<NavPosRun<T>> read,
			java.util.function.Consumer<NavPosRun<T>> apply, List<String> warnings) {
		long started = System.nanoTime();
		Map<String, Object> first = null;
		int handed = 0;
		int rounds = 0;
		int previousToHand = Integer.MAX_VALUE;
		NavPosRun<T> last;
		while (true) {
			last = read.get();
			rounds++;
			if (first == null) {
				first = new LinkedHashMap<>(last.getSummary());
			}
			if (!last.getHanded().isEmpty()) {
				apply.accept(last);
				handed += last.getHanded().size();
			}
			int toHand = last.count("toHand");
			if (last.getSummary().containsKey("waiting") || properties.isDryRun() || last.getHanded().isEmpty()
					|| last.count("heldBackByCap") == 0 || toHand >= previousToHand) {
				break;
			}
			previousToHand = toHand;
		}
		Map<String, Object> part = new LinkedHashMap<>(first);
		part.put("handed", handed);
		part.put("rounds", rounds);
		part.put("heldBackByCap", last.count("heldBackByCap"));
		part.put("durationMs", millisSince(started));
		parts.put(key, part);
		if (last.count("heldBackByCap") > 0 && !properties.isDryRun()) {
			warnings.add(label + ": " + last.count("heldBackByCap") + " not saved, handed again at the next run");
		}
		return noWait(last.getSummary(), label, warnings);
	}

	/** Items or barcodes: one run of the sync (it applies everything itself). False (with a warning) when it waited. */
	private boolean once(Map<String, Object> parts, String key, String label, Supplier<NavPosRun<?>> read,
			List<String> warnings) {
		long started = System.nanoTime();
		NavPosRun<?> run;
		try {
			run = read.get();
		} catch (ErpSyncWarningException refused) {
			// A run of this part is already running (the scheduler and "Run now" may meet)
			Map<String, Object> part = new LinkedHashMap<>();
			part.put("refused", refused.getMessage());
			parts.put(key, part);
			warnings.add(label + ": " + refused.getMessage());
			return false;
		}
		Map<String, Object> part = new LinkedHashMap<>(run.getSummary());
		part.put("durationMs", millisSince(started));
		parts.put(key, part);
		Object guard = run.getSummary().get("guard");
		if (guard != null) {
			warnings.add(label + ": " + guard);
		}
		if (BARCODES.equals(key) && Boolean.FALSE.equals(run.getSummary().get("caughtUp"))
				&& !run.getSummary().containsKey("waiting") && guard == null) {
			warnings.add(label + ": not caught up, the next run goes on");
		}
		return noWait(run.getSummary(), label, warnings);
	}

	/** True unless the summary says the part waited (then a warning with the reason). */
	private static boolean noWait(Map<String, Object> summary, String label, List<String> warnings) {
		Object waiting = summary.get("waiting");
		if (waiting == null) {
			return true;
		}
		warnings.add(label + ": " + waiting);
		return false;
	}

	private static String label(String key) {
		Function<String, String> labels = k -> FAMILIES.equals(k) ? "Families"
				: SUB_FAMILIES.equals(k) ? "Sub-families" : ITEMS.equals(k) ? "Items" : "Barcodes";
		return labels.apply(key);
	}

	private static long millisSince(long start) {
		return (System.nanoTime() - start) / 1_000_000;
	}
}
