package com.digithink.zsretail.store.rfid.controller;

import static com.digithink.zsretail.store.rfid.controller.RfidMvc.from;
import static com.digithink.zsretail.store.rfid.controller.RfidMvc.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import com.digithink.zsretail.store.rfid.model.PosMachine;
import com.digithink.zsretail.store.rfid.model.RfidReader;

/** RFID step 7: /pos-machine, the POS machines page: create, read, update, delete. */
class PosMachineAPITest {

	private final RfidMvc fixture = new RfidMvc();
	private final MockMvc mvc = fixture.mvc;
	private final RfidReader desk = fixture.rfid.reader("Caisse 1", "192.168.100.200", 9090);

	@Test
	@DisplayName("POST: 201, the address normalised, the reader by id; GET lists it with its reader")
	void create() throws Exception {
		mvc.perform(json(post("/pos-machine"),
				"{\"name\":\"PC du serveur\",\"ipAddress\":\"0:0:0:0:0:0:0:1\",\"reader\":{\"id\":" + desk.getId() + "}}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("PC du serveur"))
				.andExpect(jsonPath("$.ipAddress").value("127.0.0.1"))
				.andExpect(jsonPath("$.reader.name").value("Caisse 1"))
				.andExpect(jsonPath("$.active").value(true));
		mvc.perform(json(post("/pos-machine"), "{\"name\":\"PC bureau\",\"ipAddress\":\"192.168.1.20\",\"reader\":null}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reader").isEmpty());
		mvc.perform(from("192.168.1.50", get("/pos-machine"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	@DisplayName("A broken rule: 400 {error, reason INVALID} (a name for an address, an address taken, a missing reader)")
	void invalid() throws Exception {
		mvc.perform(json(post("/pos-machine"), "{\"name\":\"PC caisse 1\",\"ipAddress\":\"caisse-1\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("'caisse-1' is not an IP address: write an IPv4 address such as"
						+ " 192.168.1.20 (or an IPv6 address)."))
				.andExpect(jsonPath("$.reason").value("INVALID"));
		fixture.rfid.machine("PC caisse 1", "192.168.1.11", null);
		mvc.perform(json(post("/pos-machine"), "{\"name\":\"PC caisse 2\",\"ipAddress\":\"::ffff:192.168.1.11\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("The POS machine 'PC caisse 1' already has the address 192.168.1.11."));
		mvc.perform(json(post("/pos-machine"), "{\"name\":\"PC caisse 2\",\"ipAddress\":\"192.168.1.12\",\"reader\":{\"id\":42}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("No RFID reader number 42."));
		assertEquals(1, fixture.rfid.machines.size());
	}

	@Test
	@DisplayName("PUT: 200 and the machine changed (its reader removed); 404 for a machine that does not exist")
	void update() throws Exception {
		PosMachine machine = fixture.rfid.machine("PC caisse 1", "192.168.1.11", desk);
		mvc.perform(json(put("/pos-machine/" + machine.getId()), "{\"name\":\"PC caisse 1\",\"ipAddress\":\"192.168.1.12\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ipAddress").value("192.168.1.12"))
				.andExpect(jsonPath("$.reader").isEmpty());
		mvc.perform(json(put("/pos-machine/99"), "{\"name\":\"X\",\"ipAddress\":\"192.168.1.99\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("No POS machine number 99."));
	}

	@Test
	@DisplayName("DELETE: 204, then 404")
	void delete_() throws Exception {
		PosMachine machine = fixture.rfid.machine("PC caisse 1", "192.168.1.11", desk);
		mvc.perform(from("192.168.1.50", delete("/pos-machine/" + machine.getId()))).andExpect(status().isNoContent());
		assertTrue(fixture.rfid.machines.isEmpty());
		mvc.perform(from("192.168.1.50", delete("/pos-machine/" + machine.getId()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.reason").value("NOT_FOUND"));
	}
}
