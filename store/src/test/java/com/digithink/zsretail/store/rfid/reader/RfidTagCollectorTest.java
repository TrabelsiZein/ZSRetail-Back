package com.digithink.zsretail.store.rfid.reader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RFID step 4: each tag once, with its best signal and its count, whatever thread reports it. */
class RfidTagCollectorTest {

	@Test
	@DisplayName("Many reports of a tag: once, its best signal, how many times; sorted by EPC; trimmed, upper case")
	void duplicates() {
		RfidTagCollector collector = new RfidTagCollector();
		collector.seen("E200B", -60);
		collector.seen("e200a", -55);
		collector.seen(" E200B ", -48.5);
		collector.seen("E200B", -70);
		List<RfidTag> tags = collector.tags();
		assertEquals(2, tags.size());
		assertEquals("E200A", tags.get(0).getEpc());
		assertEquals(-55, tags.get(0).getBestRssi());
		assertEquals(1, tags.get(0).getSeen());
		assertEquals("E200B", tags.get(1).getEpc());
		assertEquals(-48.5, tags.get(1).getBestRssi());
		assertEquals(3, tags.get(1).getSeen());
	}

	@Test
	@DisplayName("An empty EPC is ignored; no report, no tag")
	void empty() {
		RfidTagCollector collector = new RfidTagCollector();
		assertTrue(collector.tags().isEmpty());
		collector.seen(null, -50);
		collector.seen("  ", -50);
		assertTrue(collector.tags().isEmpty());
	}

	@Test
	@DisplayName("Reports from 8 threads at once: no report lost")
	void threads() throws Exception {
		RfidTagCollector collector = new RfidTagCollector();
		CountDownLatch start = new CountDownLatch(1);
		List<Thread> threads = new ArrayList<>();
		for (int t = 0; t < 8; t++) {
			Thread thread = new Thread(() -> {
				try {
					start.await();
				} catch (InterruptedException e) {
					return;
				}
				for (int i = 0; i < 1000; i++) {
					collector.seen("TAG" + (i % 10), -40 - (i % 7));
				}
			});
			thread.start();
			threads.add(thread);
		}
		start.countDown();
		for (Thread thread : threads) {
			thread.join(10_000);
		}
		List<RfidTag> tags = collector.tags();
		assertEquals(10, tags.size());
		assertEquals(8000, tags.stream().mapToInt(RfidTag::getSeen).sum());
		tags.forEach(tag -> assertTrue(tag.getBestRssi() >= -46 && tag.getBestRssi() <= -40, tag.toString()));
	}
}
