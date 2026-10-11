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

import com.digithink.zsretail.store.rfid.model.RfidReader;

/** RFID step 7: /rfid-reader, the RFID readers page: create, read, update, delete, Test connection, Test read. */
class RfidReaderAPITest {

	private final RfidMvc fixture = new RfidMvc();
	private final MockMvc mvc = fixture.mvc;

	@Test
	@DisplayName("POST: 201 and the reader with its defaults; GET lists it and gets it by id")
	void create() throws Exception {
		mvc.perform(json(post("/rfid-reader"), "{\"name\":\" Caisse 1 \",\"ipAddress\":\"192.168.100.200\",\"port\":9090}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.name").value("Caisse 1"))
				.andExpect(jsonPath("$.power").value(30))
				.andExpect(jsonPath("$.readTimeMs").value(1500))
				.andExpect(jsonPath("$.active").value(true));
		mvc.perform(from("192.168.1.50", get("/rfid-reader"))).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Caisse 1"));
		mvc.perform(from("192.168.1.50", get("/rfid-reader/1"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.ipAddress").value("192.168.100.200"));
	}

	@Test
	@DisplayName("A broken rule: 400 {error, reason INVALID}, nothing saved")
	void invalid() throws Exception {
		mvc.perform(json(post("/rfid-reader"), "{\"name\":\"Caisse 1\",\"ipAddress\":\"reader-1\",\"port\":9090}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("The reader's address must be an IPv4 address such as 192.168.1.50,"
						+ " not 'reader-1'."))
				.andExpect(jsonPath("$.reason").value("INVALID"));
		mvc.perform(json(post("/rfid-reader"), "{\"name\":\"Caisse 1\",\"ipAddress\":\"10.0.0.1\",\"port\":9090,\"power\":31}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("The reading power must be from 0 to 30, not 31."));
		assertEquals(0, fixture.rfid.readers.size());
	}

	@Test
	@DisplayName("PUT: 200 and the reader changed; 404 for a reader that does not exist")
	void update() throws Exception {
		RfidReader reader = fixture.rfid.reader("Caisse 1", "192.168.100.200", 9090);
		mvc.perform(json(put("/rfid-reader/" + reader.getId()),
				"{\"name\":\"Caisse 1\",\"ipAddress\":\"192.168.100.200\",\"port\":9090,\"power\":20,\"readTimeMs\":800}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(reader.getId()))
				.andExpect(jsonPath("$.power").value(20))
				.andExpect(jsonPath("$.readTimeMs").value(800));
		mvc.perform(json(put("/rfid-reader/99"), "{\"name\":\"X\",\"ipAddress\":\"10.0.0.9\",\"port\":9090}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("No RFID reader number 99."))
				.andExpect(jsonPath("$.reason").value("NOT_FOUND"));
		assertEquals(1, fixture.rfid.readers.size());
	}

	@Test
	@DisplayName("DELETE: 400 naming the POS machines while linked, 204 once free, 404 when there is none")
	void delete_() throws Exception {
		RfidReader reader = fixture.rfid.reader("Caisse 1", "192.168.100.200", 9090);
		fixture.rfid.machine("PC caisse 1", "192.168.1.11", reader);
		mvc.perform(from("192.168.1.50", delete("/rfid-reader/" + reader.getId()))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("This reader is used by the POS machine PC caisse 1: give them"
						+ " another reader, or none, first."));
		fixture.rfid.machines.clear();
		mvc.perform(from("192.168.1.50", delete("/rfid-reader/" + reader.getId()))).andExpect(status().isNoContent());
		assertFalse(fixture.rfid.readers.containsKey(reader.getId()));
		mvc.perform(from("192.168.1.50", delete("/rfid-reader/" + reader.getId()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.reason").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("Test connection: the reader's information; 503 when it does not answer")
	void testConnection() throws Exception {
		RfidReader reader = fixture.rfid.reader("Caisse 1", "192.168.100.200", 9090);
		mvc.perform(from("192.168.1.50", post("/rfid-reader/" + reader.getId() + "/test-connection")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.readerName").value("Caisse 1"))
				.andExpect(jsonPath("$.address").value("192.168.100.200:9090"))
				.andExpect(jsonPath("$.information").value("FAKE reader at 192.168.100.200:9090"));
		fixture.device.unreachable("192.168.100.200:9090");
		mvc.perform(from("192.168.1.50", post("/rfid-reader/" + reader.getId() + "/test-connection")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.reason").value("UNREACHABLE"));
	}

	@Test
	@DisplayName("Test read: the tags, the reader's name, no POS machine; 409 for an inactive reader; 404 for none")
	void testRead() throws Exception {
		RfidReader reader = fixture.rfid.reader("Caisse 1", "192.168.100.200", 9090);
		fixture.device.report("192.168.100.200:9090", "E2001", -50).report("192.168.100.200:9090", "E2001", -44);
		mvc.perform(from("192.168.1.50", post("/rfid-reader/" + reader.getId() + "/test-read")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.posMachineName").isEmpty())
				.andExpect(jsonPath("$.readerName").value("Caisse 1"))
				.andExpect(jsonPath("$.readTimeMs").value(1500))
				.andExpect(jsonPath("$.tags[0].epc").value("E2001"))
				.andExpect(jsonPath("$.tags[0].bestRssi").value(-44.0))
				.andExpect(jsonPath("$.tags[0].seen").value(2));
		reader.setActive(false);
		mvc.perform(from("192.168.1.50", post("/rfid-reader/" + reader.getId() + "/test-read")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("The RFID reader 'Caisse 1' is inactive."))
				.andExpect(jsonPath("$.reason").value("NOT_READY"));
		mvc.perform(from("192.168.1.50", post("/rfid-reader/99/test-read"))).andExpect(status().isNotFound());
	}
}
