package com.digithink.zsretail.core.erp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 3.1.0: a page read of the catalogue engine that fails on an I/O error or a timeout (the VPN drop of 17/10 11:33) is
 * read again once, 5 s later, with one WARN line; the step then goes on as if nothing happened, its checkpoint moved
 * only by the read that succeeded. A second failure fails the step as before; another failure is not retried.
 */
class CatalogueReadRetryTest {

	private CatalogueTestBed bed;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() {
		bed = new CatalogueTestBed();
		ErpItemDTO tie = new ErpItemDTO();
		tie.setExternalId("14ZDU05~001");
		tie.setCode("14ZDU05~001");
		tie.setName("CRAVATE");
		tie.setUnitPrice(new BigDecimal("19.5"));
		tie.setDefaultVAT(19);
		tie.setActive(Boolean.TRUE);
		bed.items.put(CatalogueTestBed.ARGENTE, new ArrayList<>(Collections.singletonList(tie)));
		logs = new ListAppender<>();
		logs.start();
		logger().addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		logger().detachAppender(logs);
	}

	private static Logger logger() {
		return (Logger) LoggerFactory.getLogger(CatalogueEngine.class);
	}

	private List<String> warnings() {
		return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.toList());
	}

	/** What a connector throws on a VPN drop: its own exception, the I/O error as its cause. */
	private static RuntimeException vpnDrop() {
		return new IllegalStateException("zen: reading the page stockPoints failed: no answer or timeout",
				new UncheckedIOException(new ConnectException("Connect to 192.168.10.124:7048 failed: Connect timed out")));
	}

	@Test
	@DisplayName("First read throws an I/O error, the second answers: the step succeeds, one WARN, one 5 s pause")
	void retriedOnce() {
		bed.itemReadFailures.add(vpnDrop());

		CatalogueRun<ErpItemDTO> run = bed.engine.items();

		assertEquals(1, run.count("new"), "the page read again and applied");
		assertTrue(bed.tables.itemByCode("14ZDU05~001").isPresent());
		assertEquals(Collections.singletonList(Duration.ofSeconds(5)), bed.pauses);
		List<String> warnings = warnings();
		assertEquals(1, warnings.size(), warnings.toString());
		assertEquals("ERP catalogue: reading the page ITEMS of ARGENTE failed (ConnectException: Connect to"
				+ " 192.168.10.124:7048 failed: Connect timed out), retry in 5 s (1/1)", warnings.get(0));
	}

	@Test
	@DisplayName("Two failures in a row: the step fails as before, with the second error; one retry only")
	void secondFailureFailsTheStep() {
		RuntimeException second = new IllegalStateException("again", new SocketTimeoutException("Read timed out"));
		bed.itemReadFailures.add(vpnDrop());
		bed.itemReadFailures.add(second);

		assertSame(second, assertThrows(IllegalStateException.class, () -> bed.engine.items()));
		assertEquals(1, bed.pauses.size());
		assertEquals(1, warnings().size());
	}

	@Test
	@DisplayName("A failure that is not an I/O error or a timeout (a refused answer) is not retried")
	void otherFailureNotRetried() {
		RuntimeException refused = new IllegalStateException("zen: the page stockPoints answered HTTP 401");
		bed.itemReadFailures.add(refused);

		assertSame(refused, assertThrows(IllegalStateException.class, () -> bed.engine.items()));
		assertTrue(bed.pauses.isEmpty());
		assertTrue(warnings().isEmpty());
	}
}
