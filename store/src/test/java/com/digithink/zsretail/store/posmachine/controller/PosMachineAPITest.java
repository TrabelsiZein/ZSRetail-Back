package com.digithink.zsretail.store.posmachine.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.service.PosMachineService;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * /pos-machine, the POS machines page (RFID step 7; every store since 3.2.0): create, read, update, delete, and
 * /pos-machine/my-machine. Spring MVC over the controller (standalone MockMvc: the real mappings, JSON and exception
 * handler), with RFID (the reader repository there) and without.
 */
class PosMachineAPITest {

	private final InMemoryRfid rfid = new InMemoryRfid();
	private final MockMvc mvc = mvc(rfid.machineService);
	private final RfidReader desk = rfid.reader("Caisse 1", "192.168.100.200", 9090);

	private static MockMvc mvc(PosMachineService service) {
		PosMachineAPI api = new PosMachineAPI();
		try {
			Field field = _BaseController.class.getDeclaredField("service"); // Spring injects it
			field.setAccessible(true);
			field.set(api, service);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		return MockMvcBuilders.standaloneSetup(api).build();
	}

	/** A request from this address, as Tomcat gives it, asking JSON like the frontend (axios). */
	private static MockHttpServletRequestBuilder from(String remoteAddress, MockHttpServletRequestBuilder request) {
		return request.accept(MediaType.APPLICATION_JSON).with(r -> {
			r.setRemoteAddr(remoteAddress);
			return r;
		});
	}

	/** A request with this JSON body, from a machine of the store. */
	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return from("192.168.1.50", request).contentType(MediaType.APPLICATION_JSON).content(body);
	}

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
		rfid.machine("PC caisse 1", "192.168.1.11", null);
		mvc.perform(json(post("/pos-machine"), "{\"name\":\"PC caisse 2\",\"ipAddress\":\"::ffff:192.168.1.11\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("The POS machine 'PC caisse 1' already has the address 192.168.1.11."));
		mvc.perform(json(post("/pos-machine"), "{\"name\":\"PC caisse 2\",\"ipAddress\":\"192.168.1.12\",\"reader\":{\"id\":42}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("No RFID reader number 42."));
		assertEquals(1, rfid.machines.size());
	}

	@Test
	@DisplayName("PUT: 200 and the machine changed (its reader removed); 404 for a machine that does not exist")
	void update() throws Exception {
		PosMachine machine = rfid.machine("PC caisse 1", "192.168.1.11", desk);
		mvc.perform(json(put("/pos-machine/" + machine.getId()), "{\"name\":\"PC caisse 1\",\"ipAddress\":\"192.168.1.12\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ipAddress").value("192.168.1.12"))
				.andExpect(jsonPath("$.reader").isEmpty());
		mvc.perform(json(put("/pos-machine/99"), "{\"name\":\"X\",\"ipAddress\":\"192.168.1.99\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("No POS machine number 99."))
				.andExpect(jsonPath("$.reason").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("DELETE: 204, then 404")
	void delete_() throws Exception {
		PosMachine machine = rfid.machine("PC caisse 1", "192.168.1.11", desk);
		mvc.perform(from("192.168.1.50", delete("/pos-machine/" + machine.getId()))).andExpect(status().isNoContent());
		assertTrue(rfid.machines.isEmpty());
		mvc.perform(from("192.168.1.50", delete("/pos-machine/" + machine.getId()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.reason").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("GET /my-machine: the address seen (normalised) and the machine recorded there; none: the address only")
	void myMachine() throws Exception {
		PosMachine lan = rfid.machine("PC caisse 1", "192.168.1.11", null);
		mvc.perform(from("::ffff:192.168.1.11", get("/pos-machine/my-machine"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.address").value("192.168.1.11"))
				.andExpect(jsonPath("$.posMachineId").value(lan.getId()))
				.andExpect(jsonPath("$.posMachineName").value("PC caisse 1"))
				.andExpect(jsonPath("$.posMachineActive").value(true));
		mvc.perform(from("0:0:0:0:0:0:0:1", get("/pos-machine/my-machine"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.address").value("127.0.0.1"))
				.andExpect(jsonPath("$.posMachineId").isEmpty())
				.andExpect(jsonPath("$.posMachineName").isEmpty())
				.andExpect(jsonPath("$.posMachineActive").isEmpty());
	}

	@Test
	@DisplayName("Without RFID: create, my-machine and update work; a reader sent is ignored (none on a new machine, the"
			+ " one saved kept)")
	void withoutRfid() throws Exception {
		MockMvc off = mvc(rfid.machineServiceWithoutRfid);
		off.perform(json(post("/pos-machine"), "{\"name\":\"PC caisse 1\",\"ipAddress\":\"192.168.1.11\",\"reader\":{\"id\":42}}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.reader").isEmpty());
		off.perform(from("192.168.1.11", get("/pos-machine/my-machine"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.posMachineName").value("PC caisse 1"));

		PosMachine linked = rfid.machine("PC caisse 2", "192.168.1.12", desk);
		off.perform(json(put("/pos-machine/" + linked.getId()), "{\"name\":\"PC caisse 2 bis\",\"ipAddress\":\"192.168.1.12\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("PC caisse 2 bis"))
				.andExpect(jsonPath("$.reader.name").value("Caisse 1"));
		off.perform(json(post("/pos-machine"), "{\"name\":\"pc caisse 1\",\"ipAddress\":\"192.168.1.13\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.reason").value("INVALID"));
	}
}
