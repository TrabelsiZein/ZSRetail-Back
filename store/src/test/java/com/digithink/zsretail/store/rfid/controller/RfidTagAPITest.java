package com.digithink.zsretail.store.rfid.controller;

import static com.digithink.zsretail.store.rfid.controller.RfidMvc.from;
import static com.digithink.zsretail.store.rfid.controller.RfidMvc.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.store.rfid.model.RfidTag;
import com.digithink.zsretail.store.rfid.model.RfidTagStatus;

/** RFID tags step: /rfid-tag, the Tags page: the page of tags with filters, move, delete, and their refusals. */
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
