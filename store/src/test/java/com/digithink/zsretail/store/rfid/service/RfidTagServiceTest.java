package com.digithink.zsretail.store.rfid.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.data.domain.Page;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.dto.RfidTagDTO;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;
import com.digithink.zsretail.store.support.InMemoryRfid;

/** RFID tags step: the EPC rule, the Tags page (filters, pages), move and delete (IN_STOCK only), the count per item. */
class RfidTagServiceTest {

	private final InMemoryRfid rfid = new InMemoryRfid();
	private final Item shirt = rfid.items.item("SHIRT", 20.0, null);
	private final Item jeans = rfid.items.item("JEANS", 50.0, null);

	private static void assertRefused(Reason reason, String message, Executable call) {
		RfidException e = assertThrows(RfidException.class, call);
		assertEquals(reason, e.getReason());
		assertEquals(message, e.getMessage());
	}

	private static List<String> epcs(Page<RfidTagDTO> page) {
		return page.getContent().stream().map(RfidTagDTO::getEpc).collect(Collectors.toList());
	}

	@Test
	@DisplayName("EPC: trimmed and upper case; hexadecimal digits only, at most 128; anything else refused")
	void epcRule() {
		assertEquals("E2004707A8E06026B2D2010D", RfidTagService.normaliseEpc("  e2004707a8e06026b2d2010d "));
		assertEquals("0", RfidTagService.normaliseEpc("0"));
		String longest = new String(new char[128]).replace('\0', 'A');
		assertEquals(longest, RfidTagService.normaliseEpc(longest));
		for (String bad : Arrays.asList(null, "", "  ", "E200-4707", "E2004707G1", "E200 4707", longest + "A")) {
			RfidException e = assertThrows(RfidException.class, () -> RfidTagService.normaliseEpc(bad), String.valueOf(bad));
			assertEquals(Reason.INVALID, e.getReason());
			assertTrue(e.getMessage().endsWith("is not an RFID tag (EPC): hexadecimal digits only, at most 128."),
					e.getMessage());
		}
	}

	@Test
	@DisplayName("Tags page: newest first, with the item's code and name; filters item, status, a part of the EPC (any case)")
	void list() {
		rfid.tag("E2001111", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("E2002222", shirt, RfidTagStatus.SOLD);
		rfid.tag("E2003333", jeans, RfidTagStatus.IN_STOCK);

		Page<RfidTagDTO> all = rfid.tagService.list(null, null, null, 0, 20);
		assertEquals(Arrays.asList("E2003333", "E2002222", "E2001111"), epcs(all));
		assertEquals("JEANS", all.getContent().get(0).getItemCode());
		assertEquals("Item JEANS", all.getContent().get(0).getItemName());
		assertEquals(jeans.getId(), all.getContent().get(0).getItemId());

		assertEquals(Arrays.asList("E2002222", "E2001111"), epcs(rfid.tagService.list(shirt.getId(), null, null, 0, 20)));
		assertEquals(Arrays.asList("E2003333", "E2001111"),
				epcs(rfid.tagService.list(null, RfidTagStatus.IN_STOCK, null, 0, 20)));
		assertEquals(Arrays.asList("E2002222"), epcs(rfid.tagService.list(null, null, " 2222 ", 0, 20)));
		assertEquals(Arrays.asList("E2003333"), epcs(rfid.tagService.list(null, null, "e2003", 0, 20)), "any case");
		assertEquals(3, rfid.tagService.list(null, null, "  ", 0, 20).getTotalElements(), "blank: no filter");

		Page<RfidTagDTO> second = rfid.tagService.list(null, null, null, 1, 2);
		assertEquals(Arrays.asList("E2001111"), epcs(second));
		assertEquals(3, second.getTotalElements());
		assertEquals(200, rfid.tagService.list(null, null, null, 0, 5000).getSize(), "at most 200 a page");
	}

	@Test
	@DisplayName("EPC filter: a ready LIKE pattern of its hexadecimal digits (no concat in the query, no wildcard passes); none: null")
	void epcPattern() {
		assertEquals("%E2004707%", RfidTagService.epcPattern(" e200-4707 "));
		assertEquals("%E200%", RfidTagService.epcPattern("%E200_%"));
		assertEquals(null, RfidTagService.epcPattern(null));
		assertEquals(null, RfidTagService.epcPattern("  "));
		assertEquals(null, RfidTagService.epcPattern("%_xyz"));
	}

	@Test
	@DisplayName("Move: an IN_STOCK tag to another item; the same item changes nothing; SOLD refused; tag or item missing")
	void move() {
		RfidTag tag = rfid.tag("E2001111", shirt, RfidTagStatus.IN_STOCK);
		RfidTagDTO moved = rfid.tagService.move(tag.getId(), jeans.getId());
		assertEquals(jeans.getId(), moved.getItemId());
		assertEquals(jeans, rfid.tags.get(tag.getId()).getItem());
		assertEquals("tester", tag.getUpdatedBy());
		assertEquals(rfid.now, tag.getUpdatedAt());

		tag.setUpdatedBy(null);
		rfid.tagService.move(tag.getId(), jeans.getId());
		assertEquals(null, tag.getUpdatedBy(), "the same item: nothing written");

		RfidTag sold = rfid.tag("E2002222", shirt, RfidTagStatus.SOLD);
		assertRefused(Reason.NOT_READY, "The tag E2002222 is sold: it is not moved.",
				() -> rfid.tagService.move(sold.getId(), jeans.getId()));
		assertEquals(shirt, sold.getItem());
		assertRefused(Reason.NOT_FOUND, "No RFID tag number 99.", () -> rfid.tagService.move(99L, jeans.getId()));
		assertRefused(Reason.NOT_FOUND, "No item number 5.", () -> rfid.tagService.move(tag.getId(), 5L));
		assertRefused(Reason.INVALID, "Choose an item.", () -> rfid.tagService.move(tag.getId(), null));
		Item pack = rfid.items.item("PACK-1", 60.0, null);
		pack.setType(com.digithink.zsretail.core.model.enumeration.ItemType.PACKAGE);
		assertRefused(Reason.INVALID, "The item PACK-1 is a pack: a pack holds no RFID tag. Link the tags to the items of its"
				+ " pieces.", () -> rfid.tagService.move(tag.getId(), pack.getId()));
		assertEquals(jeans, tag.getItem(), "not moved to the pack");
	}

	@Test
	@DisplayName("Delete: an IN_STOCK tag; a SOLD tag is kept (the record of the piece sold); missing: NOT_FOUND")
	void delete() {
		RfidTag tag = rfid.tag("E2001111", shirt, RfidTagStatus.IN_STOCK);
		RfidTag sold = rfid.tag("E2002222", shirt, RfidTagStatus.SOLD);
		rfid.tagService.delete(tag.getId());
		assertFalse(rfid.tags.containsKey(tag.getId()));
		assertRefused(Reason.NOT_READY, "The tag E2002222 is sold: it is kept as the record of the piece sold.",
				() -> rfid.tagService.delete(sold.getId()));
		assertTrue(rfid.tags.containsKey(sold.getId()));
		assertRefused(Reason.NOT_FOUND, "No RFID tag number " + tag.getId() + ".", () -> rfid.tagService.delete(tag.getId()));
	}

	@Test
	@DisplayName("Count per item, whatever the status (the item deletion check)")
	void count() {
		rfid.tag("E2001111", shirt, RfidTagStatus.IN_STOCK);
		rfid.tag("E2002222", shirt, RfidTagStatus.SOLD);
		assertEquals(2, rfid.tagService.countForItem(shirt.getId()));
		assertEquals(0, rfid.tagService.countForItem(jeans.getId()));
		assertEquals("This item has 2 RFID tags: move them to another item or delete them first (RFID, Tags page).",
				new RfidItemDeletionCheck(rfid.tagService).refusal(shirt.getId()));
		assertEquals(null, new RfidItemDeletionCheck(rfid.tagService).refusal(jeans.getId()));
		rfid.tag("E2003333", jeans, RfidTagStatus.IN_STOCK);
		assertEquals("This item has 1 RFID tag: move them to another item or delete them first (RFID, Tags page).",
				new RfidItemDeletionCheck(rfid.tagService).refusal(jeans.getId()));
	}
}
