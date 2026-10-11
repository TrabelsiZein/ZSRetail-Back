package com.digithink.zsretail.store.rfid.controller;

import static com.digithink.zsretail.store.rfid.controller.RfidMvc.from;
import static com.digithink.zsretail.store.rfid.controller.RfidMvc.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;

/** RFID tags step: /rfid-tag, the Tags page (list, move, delete) and the assignment (read, save), with their refusals. */
class RfidTagAPITest {

	private final RfidMvc fixture = new RfidMvc();
	private final MockMvc mvc = fixture.mvc;
	private final Item shirt = fixture.rfid.items.item("SHIRT", 20.0, null);
	private final Item jeans = fixture.rfid.items.item("JEANS", 50.0, null);

	@Test
	@DisplayName("GET: a page of tags, newest first, with the item; the filters item, status and epc")
	void list() throws Exception {
		fixture.rfid.tag("E2001111", shirt, RfidTagStatus.IN_STOCK);
		fixture.rfid.tag("E2002222", jeans, RfidTagStatus.SOLD);
		mvc.perform(from("192.168.1.50", get("/rfid-tag"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.content[0].epc").value("E2002222"))
				.andExpect(jsonPath("$.content[0].itemCode").value("JEANS"))
				.andExpect(jsonPath("$.content[0].status").value("SOLD"))
				.andExpect(jsonPath("$.content[0].sourceType").value("MANUAL"));
		mvc.perform(from("192.168.1.50", get("/rfid-tag").param("itemId", String.valueOf(shirt.getId()))))
				.andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].epc").value("E2001111"));
		mvc.perform(from("192.168.1.50", get("/rfid-tag").param("status", "SOLD").param("epc", "e200")))
				.andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].epc").value("E2002222"));
	}

	@Test
	@DisplayName("PUT /{id}/item: moved; a SOLD tag 409; a missing item 404")
	void move() throws Exception {
		RfidTag tag = fixture.rfid.tag("E2001111", shirt, RfidTagStatus.IN_STOCK);
		RfidTag sold = fixture.rfid.tag("E2002222", shirt, RfidTagStatus.SOLD);
		mvc.perform(json(put("/rfid-tag/" + tag.getId() + "/item"), "{\"itemId\":" + jeans.getId() + "}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.itemCode").value("JEANS"));
		assertEquals(jeans, tag.getItem());
		mvc.perform(json(put("/rfid-tag/" + sold.getId() + "/item"), "{\"itemId\":" + jeans.getId() + "}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.reason").value("NOT_READY"))
				.andExpect(jsonPath("$.error").value("The tag E2002222 is sold: it is not moved."));
		mvc.perform(json(put("/rfid-tag/" + tag.getId() + "/item"), "{\"itemId\":4}"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("No item number 4."));
	}

	@Test
	@DisplayName("POST /assignment/read: the four groups with the counts; refusals with their status (no reader 400,"
			+ " unreachable 503)")
	void assignmentRead() throws Exception {
		RfidReader desk = fixture.rfid.reader("Caisse 1", "192.168.100.200", 9090);
		fixture.rfid.tag("E200BBBB", jeans, RfidTagStatus.IN_STOCK);
		fixture.device.report("192.168.100.200:9090", "E200AAAA", -45).report("192.168.100.200:9090", "E200BBBB", -60);
		mvc.perform(json(post("/rfid-tag/assignment/read"),
				"{\"readerId\":" + desk.getId() + ",\"itemId\":" + shirt.getId() + ",\"expectedQuantity\":2}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.readerName").value("Caisse 1"))
				.andExpect(jsonPath("$.itemCode").value("SHIRT"))
				.andExpect(jsonPath("$.expectedQuantity").value(2))
				.andExpect(jsonPath("$.newCount").value(1))
				.andExpect(jsonPath("$.otherItemCount").value(1))
				.andExpect(jsonPath("$.newTags[0].epc").value("E200AAAA"))
				.andExpect(jsonPath("$.newTags[0].weak").value(false))
				.andExpect(jsonPath("$.otherItem[0].otherItemName").value("Item JEANS"))
				.andExpect(jsonPath("$.otherItem[0].weak").value(true))
				.andExpect(jsonPath("$.weakMarginDb").value(10));
		mvc.perform(json(post("/rfid-tag/assignment/read"), "{\"itemId\":" + shirt.getId() + "}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Choose a reader."));
		fixture.device.unreachable("192.168.100.200:9090");
		mvc.perform(json(post("/rfid-tag/assignment/read"), "{\"readerId\":" + desk.getId() + ",\"itemId\":" + shirt.getId() + "}"))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.reason").value("UNREACHABLE"));
	}

	@Test
	@DisplayName("POST /assignment/save: linked, already on the item, refused; a reception without its line 400")
	void assignmentSave() throws Exception {
		fixture.rfid.tag("E200BBBB", jeans, RfidTagStatus.IN_STOCK);
		mvc.perform(json(post("/rfid-tag/assignment/save"),
				"{\"itemId\":" + shirt.getId() + ",\"epcs\":[\"E200AAAA\",\"E200BBBB\"],\"sourceType\":\"RECEPTION\","
						+ "\"sourceDocument\":\"BL-2026-0042\",\"sourceLine\":3}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.linked[0]").value("E200AAAA"))
				.andExpect(jsonPath("$.refused[0].epc").value("E200BBBB"))
				.andExpect(jsonPath("$.refused[0].reason").value("OTHER_ITEM"))
				.andExpect(jsonPath("$.refused[0].itemCode").value("JEANS"));
		assertEquals("BL-2026-0042", fixture.rfid.tagRepository.findByEpc("E200AAAA").get().getSourceDocument());
		mvc.perform(json(post("/rfid-tag/assignment/save"),
				"{\"itemId\":" + shirt.getId() + ",\"epcs\":[\"E200AAAA\"]}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.alreadyOnItem[0]").value("E200AAAA"))
				.andExpect(jsonPath("$.linked.length()").value(0));
		mvc.perform(json(post("/rfid-tag/assignment/save"),
				"{\"itemId\":" + shirt.getId() + ",\"epcs\":[\"E200CCCC\"],\"sourceType\":\"RECEPTION\",\"sourceDocument\":\"BL-1\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID"));
	}

	@Test
	@DisplayName("DELETE: 204 in stock; 409 sold; 404 when none")
	void delete_() throws Exception {
		RfidTag tag = fixture.rfid.tag("E2001111", shirt, RfidTagStatus.IN_STOCK);
		RfidTag sold = fixture.rfid.tag("E2002222", shirt, RfidTagStatus.SOLD);
		mvc.perform(from("192.168.1.50", delete("/rfid-tag/" + tag.getId()))).andExpect(status().isNoContent());
		assertFalse(fixture.rfid.tags.containsKey(tag.getId()));
		mvc.perform(from("192.168.1.50", delete("/rfid-tag/" + sold.getId()))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.reason").value("NOT_READY"));
		mvc.perform(from("192.168.1.50", delete("/rfid-tag/" + tag.getId()))).andExpect(status().isNotFound());
	}
}
