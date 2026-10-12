package com.digithink.zsretail.store.rfid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.dto.RfidAssignmentRequest;
import com.digithink.zsretail.store.rfid.dto.RfidPosReadResult;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.support.FakeRfidReader;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * RFID: one read at a time per reader, across every way of reading it (the POS cart's POST /rfid/pos-read, the return
 * screen's POST /rfid/read, the tag assignment, the Test read), from the same POS machine or from two machines linked to
 * the same reader: the second call is BUSY at once (409), never queued, and the reader is free again after the read.
 * The release after a failure of the reader: RfidReadServiceTest.readerFailures.
 */
class RfidReaderBusyTest {

	private static final String BUSY = "The RFID reader 'Caisse 1' is busy with another read: try again in a moment.";

	private final InMemoryRfid rfid = new InMemoryRfid();
	private final FakeRfidReader device = new FakeRfidReader();
	/** One read service for all, as in the application (one bean, one lock per reader). */
	private final RfidReadService reads = new RfidReadService(rfid.readerService, rfid.machineService, device);
	/** The tickets are not used by a read. */
	private final RfidPosService pos = new RfidPosService(reads, rfid.machineService, rfid.tagRepository,
			rfid.saleTagRepository, null);
	private final RfidAssignmentService assignment = new RfidAssignmentService(reads, rfid.tagService,
			rfid.tagRepository, TransactionOperations.withoutTransaction(), () -> "tester", () -> rfid.now);

	private final RfidReader desk = rfid.reader("Caisse 1", "192.168.100.200", 9090);
	private final RfidReader door = rfid.reader("Entrée", "192.168.100.201", 9090);
	private final Item shirt = rfid.items.item("SHIRT", 20.0, null);

	private static void assertBusy(Executable call) {
		RfidException e = assertThrows(RfidException.class, call);
		assertEquals(Reason.BUSY, e.getReason());
		assertEquals(BUSY, e.getMessage());
	}

	private RfidAssignmentRequest.Read assignmentRead(Long readerId) {
		RfidAssignmentRequest.Read request = new RfidAssignmentRequest.Read();
		request.setReaderId(readerId);
		request.setItemId(shirt.getId());
		return request;
	}

	@Test
	@DisplayName("A POS read in progress: every other read of its reader is BUSY at once, from the same machine or another"
			+ " one on the reader; another reader reads meanwhile; all free again after the read")
	void oneReadAtATimePerReader() throws Exception {
		device.report("192.168.100.200:9090", "E2001", -40);
		rfid.machine("PC caisse 1", "192.168.1.11", desk);
		rfid.machine("PC caisse 2", "192.168.1.12", desk);
		rfid.machine("PC entrée", "192.168.1.13", door);

		CountDownLatch release = device.holdNextRead();
		CompletableFuture<RfidPosReadResult> first = CompletableFuture.supplyAsync(() -> pos.read("192.168.1.11", null));
		device.awaitHeldRead();

		long before = System.nanoTime();
		assertBusy(() -> pos.read("192.168.1.11", null)); // the same machine, a second click
		assertBusy(() -> pos.read("192.168.1.12", null)); // another machine on the same reader
		assertBusy(() -> reads.readForRequest("192.168.1.12")); // the return screen's Read
		assertBusy(() -> assignment.read(assignmentRead(desk.getId()))); // the tag assignment
		assertBusy(() -> reads.testRead(desk.getId())); // the Test read of the readers page
		assertTrue((System.nanoTime() - before) / 1_000_000 < 1000, "answered at once, never queued");
		assertEquals("Entrée", pos.read("192.168.1.13", null).getReaderName(), "another reader reads meanwhile");

		release.countDown();
		assertEquals("PC caisse 1", first.get(5, TimeUnit.SECONDS).getPosMachineName());
		assertEquals("PC caisse 2", pos.read("192.168.1.12", null).getPosMachineName(), "free again");
		assertEquals("Caisse 1", reads.readForRequest("192.168.1.11").getReaderName());
		assertEquals("Caisse 1", assignment.read(assignmentRead(desk.getId())).getReaderName());
	}
}
