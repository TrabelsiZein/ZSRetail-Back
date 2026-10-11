package com.digithink.zsretail.store.rfid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.dto.RfidAssignmentRead;
import com.digithink.zsretail.store.rfid.dto.RfidAssignmentRequest;
import com.digithink.zsretail.store.rfid.dto.RfidAssignmentResult;
import com.digithink.zsretail.store.rfid.dto.RfidAssignmentResult.Refusal;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagSource;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.support.FakeRfidReader;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * RFID tags step: the assignment. The read sorts the tags seen into NEW, SAME_ITEM, OTHER_ITEM, SOLD with "weak"; the
 * save links only unknown tags, reports the others and never changes them, and saving twice changes nothing.
 */
class RfidAssignmentServiceTest {

	private static final String DESK = "192.168.100.200:9090";

	private final InMemoryRfid rfid = new InMemoryRfid();
	private final FakeRfidReader device = new FakeRfidReader();
	private final RfidReadService reads = new RfidReadService(rfid.readerService, rfid.machineService, device);
	private final RfidAssignmentService service = new RfidAssignmentService(reads, rfid.tagService, rfid.tagRepository,
			TransactionOperations.withoutTransaction(), () -> "tester", () -> rfid.now);

	private final RfidReader desk = rfid.reader("Caisse 1", "192.168.100.200", 9090);
	private final Item shirt = rfid.items.item("SHIRT", 20.0, null);
	private final Item jeans = rfid.items.item("JEANS", 50.0, null);

	private static RfidAssignmentRequest.Read read(Long readerId, Long itemId, Integer expected) {
		RfidAssignmentRequest.Read request = new RfidAssignmentRequest.Read();
		request.setReaderId(readerId);
		request.setItemId(itemId);
		request.setExpectedQuantity(expected);
		return request;
	}

	private static RfidAssignmentRequest.Save save(Long itemId, String... epcs) {
		RfidAssignmentRequest.Save request = new RfidAssignmentRequest.Save();
		request.setItemId(itemId);
		request.setEpcs(Arrays.asList(epcs));
		return request;
	}

	private static List<String> epcs(List<RfidAssignmentRead.Tag> tags) {
		return tags.stream().map(RfidAssignmentRead.Tag::getEpc).collect(Collectors.toList());
	}

	private static void assertRefused(Reason reason, String message, Executable call) {
		RfidException e = assertThrows(RfidException.class, call);
		assertEquals(reason, e.getReason());
		assertEquals(message, e.getMessage());
	}

	@Test
	@DisplayName("Read: the four groups, each tag with its signal and times seen; OTHER_ITEM and SOLD with their item;"
			+ " the counts, the expected quantity, the reader; nothing written")
	void groups() {
		rfid.tag("E200AAAA", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("E200BBBB", jeans, RfidTagStatus.IN_STOCK);
		rfid.tag("E200CCCC", jeans, RfidTagStatus.SOLD);
		device.report(DESK, "e200aaaa", -50).report(DESK, "E200BBBB", -51).report(DESK, "E200CCCC", -52)
				.report(DESK, "E200DDDD", -48).report(DESK, "E200DDDD", -45).report(DESK, "E200EEEE", -49);

		RfidAssignmentRead answer = service.read(read(desk.getId(), shirt.getId(), 3));
		assertEquals("Caisse 1", answer.getReaderName());
		assertEquals("SHIRT", answer.getItemCode());
		assertEquals("Item SHIRT", answer.getItemName());
		assertEquals(3, answer.getExpectedQuantity());
		assertEquals(1500, answer.getReadTimeMs());
		assertEquals(-50, answer.getMinSignal());
		assertEquals(-45.0, answer.getBestRssi());

		assertEquals(Arrays.asList("E200DDDD", "E200EEEE"), epcs(answer.getNewTags()));
		assertEquals(Arrays.asList("E200AAAA"), epcs(answer.getSameItem()));
		assertEquals(Arrays.asList("E200BBBB"), epcs(answer.getOtherItem()));
		assertEquals(Arrays.asList("E200CCCC"), epcs(answer.getSold()));
		assertEquals(2, answer.getNewCount());
		assertEquals(1, answer.getSameItemCount());
		assertEquals(1, answer.getOtherItemCount());
		assertEquals(1, answer.getSoldCount());

		RfidAssignmentRead.Tag fresh = answer.getNewTags().get(0);
		assertEquals(-45.0, fresh.getBestRssi());
		assertEquals(2, fresh.getSeen());
		assertEquals(RfidAssignmentRead.Group.NEW, fresh.getGroup());
		RfidAssignmentRead.Tag other = answer.getOtherItem().get(0);
		assertEquals(jeans.getId(), other.getOtherItemId());
		assertEquals("JEANS", other.getOtherItemCode());
		assertEquals("Item JEANS", other.getOtherItemName());
		assertEquals("JEANS", answer.getSold().get(0).getOtherItemCode());
		assertNull(answer.getSameItem().get(0).getOtherItemCode());
		assertEquals(3, rfid.tags.size(), "a read writes nothing");
	}

	@Test
	@DisplayName("Weak: a best signal below the reader's minimum signal (default -50; equal is not weak); the 5 tags on the"
			+ " reader of the real test (-23 to -44) are all strong; a minimum of -40 marks -44 only")
	void weak() {
		device.report(DESK, "E2000001", -23).report(DESK, "E2000002", -25).report(DESK, "E2000003", -25)
				.report(DESK, "E2000004", -37).report(DESK, "E2000005", -44);
		RfidAssignmentRead onReader = service.read(read(desk.getId(), shirt.getId(), null));
		assertEquals(Arrays.asList(false, false, false, false, false), weakOf(onReader), "the real test: none weak");
		assertEquals(-50, onReader.getMinSignal());

		desk.setMinSignal(-40);
		RfidAssignmentRead stricter = service.read(read(desk.getId(), shirt.getId(), null));
		assertEquals(Arrays.asList(false, false, false, false, true), weakOf(stricter));
		assertEquals(-40, stricter.getMinSignal());

		desk.setMinSignal(-50);
		device.report(DESK, "E2000006", -50).report(DESK, "E2000007", -50.5).report(DESK, "E2000008", -70);
		RfidAssignmentRead edges = service.read(read(desk.getId(), shirt.getId(), null));
		assertEquals(Arrays.asList(false, false, false, false, false, false, true, true), weakOf(edges));
		assertNull(edges.getExpectedQuantity());
	}

	private static List<Boolean> weakOf(RfidAssignmentRead answer) {
		return answer.getNewTags().stream().map(RfidAssignmentRead.Tag::isWeak).collect(Collectors.toList());
	}

	@Test
	@DisplayName("Read with no tag: empty groups, no best signal")
	void nothingSeen() {
		RfidAssignmentRead answer = service.read(read(desk.getId(), shirt.getId(), 0));
		assertEquals(0, answer.getNewCount());
		assertNull(answer.getBestRssi());
		assertEquals(0, answer.getExpectedQuantity());
	}

	@Test
	@DisplayName("Read refused: no reader chosen, no item, an inactive item, an expected quantity out of 0..100000; the"
			+ " reader's own refusals (inactive, busy, unreachable) as for every read; the reader not called before")
	void readRefused() throws Exception {
		assertRefused(Reason.INVALID, "Choose a reader.", () -> service.read(read(null, shirt.getId(), null)));
		assertRefused(Reason.INVALID, "Choose an item.", () -> service.read(read(desk.getId(), null, null)));
		assertRefused(Reason.NOT_FOUND, "No item number 7.", () -> service.read(read(desk.getId(), 7L, null)));
		assertRefused(Reason.INVALID, "The expected quantity is a whole number from 0 to 100000, not -1.",
				() -> service.read(read(desk.getId(), shirt.getId(), -1)));
		assertRefused(Reason.INVALID, "The expected quantity is a whole number from 0 to 100000, not 100001.",
				() -> service.read(read(desk.getId(), shirt.getId(), 100001)));
		jeans.setActive(false);
		assertRefused(Reason.INVALID, "The item JEANS is inactive: tags are linked to an active item.",
				() -> service.read(read(desk.getId(), jeans.getId(), null)));
		assertRefused(Reason.NOT_FOUND, "No RFID reader number 99.", () -> service.read(read(99L, shirt.getId(), null)));
		assertTrue(device.calls.isEmpty(), "the reader was not called: " + device.calls);

		desk.setActive(false);
		assertRefused(Reason.NOT_READY, "The RFID reader 'Caisse 1' is inactive.",
				() -> service.read(read(desk.getId(), shirt.getId(), null)));
		desk.setActive(true);
		CountDownLatch release = device.holdNextRead();
		CompletableFuture<?> first = CompletableFuture.runAsync(() -> service.read(read(desk.getId(), shirt.getId(), null)));
		device.awaitHeldRead();
		assertRefused(Reason.BUSY, "The RFID reader 'Caisse 1' is busy with another read: try again in a moment.",
				() -> service.read(read(desk.getId(), shirt.getId(), null)));
		release.countDown();
		first.get(5, TimeUnit.SECONDS);
		device.unreachable(DESK);
		assertRefused(Reason.UNREACHABLE, "The RFID reader 'Caisse 1' does not answer at 192.168.100.200:9090.",
				() -> service.read(read(desk.getId(), shirt.getId(), null)));
	}

	@Test
	@DisplayName("Save: only unknown tags are linked (IN_STOCK, MANUAL, by the user, now); same item left as it is; another"
			+ " item and sold refused and reported, never changed; EPCs normalised and counted once")
	void save() {
		RfidTag mine = rfid.tag("E200AAAA", shirt, RfidTagStatus.IN_STOCK);
		RfidTag theirs = rfid.tag("E200BBBB", jeans, RfidTagStatus.IN_STOCK);
		RfidTag sold = rfid.tag("E200CCCC", jeans, RfidTagStatus.SOLD);

		RfidAssignmentResult result = service.save(save(shirt.getId(), " e200dddd ", "E200DDDD", "E200AAAA", "E200BBBB",
				"E200CCCC", "E200EEEE", "not-an-epc"));
		assertEquals(Arrays.asList("E200DDDD", "E200EEEE"), result.getLinked());
		assertEquals(Arrays.asList("E200AAAA"), result.getAlreadyOnItem());
		assertEquals("SHIRT", result.getItemCode());
		List<RfidAssignmentResult.Refused> refused = result.getRefused();
		assertEquals(3, refused.size());
		assertEquals("not-an-epc", refused.get(0).getEpc());
		assertEquals(Refusal.INVALID, refused.get(0).getReason());
		assertEquals("'not-an-epc' is not an RFID tag (EPC).", refused.get(0).getMessage());
		assertEquals(Refusal.OTHER_ITEM, refused.get(1).getReason());
		assertEquals("JEANS", refused.get(1).getItemCode());
		assertEquals("The tag E200BBBB is on the item JEANS Item JEANS: move it on the Tags page if it is wrong.",
				refused.get(1).getMessage());
		assertEquals(Refusal.SOLD, refused.get(2).getReason());
		assertEquals("The tag E200CCCC is sold (item JEANS): it is not linked again.", refused.get(2).getMessage());

		assertEquals(jeans, theirs.getItem(), "never overridden");
		assertEquals(RfidTagStatus.SOLD, sold.getStatus());
		assertEquals(shirt, mine.getItem());
		assertEquals(5, rfid.tags.size());
		RfidTag linked = rfid.tagRepository.findByEpc("E200DDDD").get();
		assertEquals(shirt, linked.getItem());
		assertEquals(RfidTagStatus.IN_STOCK, linked.getStatus());
		assertEquals(RfidTagSource.MANUAL, linked.getSourceType());
		assertNull(linked.getSourceDocument());
		assertNull(linked.getSourceLine());
		assertEquals("tester", linked.getCreatedBy());
		assertEquals(rfid.now, linked.getCreatedAt());
	}

	@Test
	@DisplayName("Saving twice changes nothing: the second save links none, all already on the item")
	void saveTwice() {
		service.save(save(shirt.getId(), "E200DDDD", "E200EEEE"));
		RfidAssignmentResult again = service.save(save(shirt.getId(), "E200DDDD", "E200EEEE"));
		assertTrue(again.getLinked().isEmpty());
		assertEquals(Arrays.asList("E200DDDD", "E200EEEE"), again.getAlreadyOnItem());
		assertTrue(again.getRefused().isEmpty());
		assertEquals(2, rfid.tags.size());
	}

	@Test
	@DisplayName("A tag linked to another item between the read and the save: refused, never moved")
	void raceBetweenReadAndSave() {
		device.report(DESK, "E200DDDD", -50);
		assertEquals(Arrays.asList("E200DDDD"), epcs(service.read(read(desk.getId(), shirt.getId(), null)).getNewTags()));
		service.save(save(jeans.getId(), "E200DDDD")); // another user, another item
		RfidAssignmentResult result = service.save(save(shirt.getId(), "E200DDDD"));
		assertTrue(result.getLinked().isEmpty());
		assertEquals(Refusal.OTHER_ITEM, result.getRefused().get(0).getReason());
		assertEquals(jeans, rfid.tagRepository.findByEpc("E200DDDD").get().getItem());
	}

	@Test
	@DisplayName("Source RECEPTION: the delivery number (trimmed, at most 30) and the line are kept; both required")
	void reception() {
		RfidAssignmentRequest.Save request = save(shirt.getId(), "E200DDDD");
		request.setSourceType(RfidTagSource.RECEPTION);
		request.setSourceDocument(" BL-2026-0042 ");
		request.setSourceLine(3);
		service.save(request);
		RfidTag tag = rfid.tagRepository.findByEpc("E200DDDD").get();
		assertEquals(RfidTagSource.RECEPTION, tag.getSourceType());
		assertEquals("BL-2026-0042", tag.getSourceDocument());
		assertEquals(3, tag.getSourceLine());

		String message = "A reception gives its delivery number (at most 30 characters) and its line.";
		for (Object[] bad : new Object[][] { { null, 3 }, { "  ", 3 }, { "BL-1", null }, { "BL-1", 0 },
				{ new String(new char[31]).replace('\0', 'B'), 1 } }) {
			RfidAssignmentRequest.Save wrong = save(shirt.getId(), "E200EEEE");
			wrong.setSourceType(RfidTagSource.RECEPTION);
			wrong.setSourceDocument((String) bad[0]);
			wrong.setSourceLine((Integer) bad[1]);
			assertRefused(Reason.INVALID, message, () -> service.save(wrong));
		}
		assertFalse(rfid.tagRepository.findByEpc("E200EEEE").isPresent());

		RfidAssignmentRequest.Save manual = save(shirt.getId(), "E200FFFF");
		manual.setSourceDocument("BL-1");
		manual.setSourceLine(2);
		service.save(manual);
		assertNull(rfid.tagRepository.findByEpc("E200FFFF").get().getSourceDocument(), "MANUAL keeps no document");
	}

	@Test
	@DisplayName("A pack holds no tag: read and save refused with the message, nothing written, the reader not called")
	void packRefused() {
		Item pack = rfid.items.item("PACK-1", 60.0, null);
		pack.setType(com.digithink.zsretail.core.model.enumeration.ItemType.PACKAGE);
		String message = "The item PACK-1 is a pack: a pack holds no RFID tag. Link the tags to the items of its pieces.";
		assertRefused(Reason.INVALID, message, () -> service.read(read(desk.getId(), pack.getId(), null)));
		assertRefused(Reason.INVALID, message, () -> service.save(save(pack.getId(), "E200DDDD")));
		assertTrue(device.calls.isEmpty());
		assertTrue(rfid.tags.isEmpty());
	}

	@Test
	@DisplayName("Save refused: no item, an inactive item, more than 5000 EPCs; an empty list links nothing")
	void saveRefused() {
		assertRefused(Reason.INVALID, "Choose an item.", () -> service.save(save(null, "E200DDDD")));
		jeans.setActive(false);
		assertRefused(Reason.INVALID, "The item JEANS is inactive: tags are linked to an active item.",
				() -> service.save(save(jeans.getId(), "E200DDDD")));
		RfidAssignmentRequest.Save many = save(shirt.getId());
		many.setEpcs(Collections.nCopies(5001, "E200DDDD"));
		assertRefused(Reason.INVALID, "At most 5000 tags in one save, not 5001.", () -> service.save(many));
		RfidAssignmentRequest.Save none = save(shirt.getId());
		none.setEpcs(null);
		assertTrue(service.save(none).getLinked().isEmpty());
		assertTrue(rfid.tags.isEmpty());
	}
}
