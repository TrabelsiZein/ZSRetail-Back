package com.digithink.zsretail.core.erp.engine;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCatalogueSource;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * 3.1.0: the ERP's pages as the catalogue engine reads them, every connector alike. A page read that fails on an I/O
 * error or a timeout (an {@link IOException} in its causes: a VPN drop, connection refused, read timed out) is read
 * again once, {@link #RETRY_DELAY} later, with one WARN line; a second failure goes up as before and fails the step.
 * The same page is asked again (same location, time, cursor or items): no cursor or checkpoint moves before a read
 * succeeds. Any other failure (an answer the connector refuses) goes up at once.
 */
final class RetryingCatalogueSource implements ErpCatalogueSource {

	static final Duration RETRY_DELAY = Duration.ofSeconds(5);

	private static final Logger LOG = LogManager.getLogger(CatalogueEngine.class);

	/** The wait before the retry; Thread.sleep in the application, none in the tests. */
	interface Pause {
		void sleep(Duration delay) throws InterruptedException;
	}

	static final Pause SLEEP = delay -> Thread.sleep(delay.toMillis());

	private final ErpCatalogueSource source;
	private final Pause pause;

	RetryingCatalogueSource(ErpCatalogueSource source, Pause pause) {
		this.source = source;
		this.pause = pause;
	}

	@Override
	public ErpCategories categories() {
		return read(Part.CATEGORIES, "", source::categories);
	}

	@Override
	public ErpRead<ErpItemDTO> items(String locationCode) {
		return read(Part.ITEMS, " of " + locationCode, () -> source.items(locationCode));
	}

	@Override
	public ErpRead<ErpItemDTO> items(String locationCode, OffsetDateTime since) {
		return read(Part.ITEMS, " of " + locationCode, () -> source.items(locationCode, since));
	}

	@Override
	public boolean readsChangesOnly() {
		return source.readsChangesOnly();
	}

	@Override
	public ErpRead<ErpBarcodeRow> barcodesAfter(long position) {
		return read(Part.BARCODES, " after " + position, () -> source.barcodesAfter(position));
	}

	@Override
	public ErpRead<ErpBarcodeRow> barcodesOfItems(List<String> itemCodes) {
		return read(Part.BARCODES, " of " + itemCodes.size() + " items", () -> source.barcodesOfItems(itemCodes));
	}

	@Override
	public String pageLabel(Part part) {
		return source.pageLabel(part);
	}

	@Override
	public String pageUrl(Part part) {
		return source.pageUrl(part);
	}

	private <T> T read(Part part, String which, Supplier<T> page) {
		try {
			return page.get();
		} catch (RuntimeException e) {
			if (!ioFailure(e)) {
				throw e;
			}
			LOG.warn("ERP catalogue: reading the page {}{} failed ({}), retry in {} s (1/1)", source.pageLabel(part),
					which, mostSpecific(e), RETRY_DELAY.getSeconds());
			try {
				pause.sleep(RETRY_DELAY);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw e;
			}
			return page.get(); // a second failure fails the step, as before
		}
	}

	/** An IOException among the causes: connection refused or reset, unknown host, connect or read timeout. */
	static boolean ioFailure(Throwable e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof IOException) {
				return true;
			}
		}
		return false;
	}

	/** The deepest cause, e.g. "ConnectTimeoutException: Connect to 192.168.10.124:7048 failed: Connect timed out". */
	private static String mostSpecific(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
	}
}
