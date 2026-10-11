package com.digithink.zsretail.store.rfid.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.store.rfid.RfidException;
import com.digithink.zsretail.store.rfid.RfidException.Reason;
import com.digithink.zsretail.store.rfid.model.RfidReader;
import com.digithink.zsretail.store.rfid.service.RfidReadService;
import com.digithink.zsretail.store.support.FakeRfidReader;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * RFID steps 6 and 7: GET /rfid/my-machine, the address the back end sees for the request (as Tomcat gives it), the POS
 * machine recorded there and whether it can read (the reader never called); POST /rfid/read, the read with that
 * machine's reader, and its refusals; the answers of RfidErrors.
 * Spring MVC over the controller (standalone MockMvc: the real mapping and JSON), the in-memory tables, the fake reader.
 */
class RfidAPITest {

	final InMemoryRfid rfid = new InMemoryRfid();
	final FakeRfidReader device = new FakeRfidReader();
	final RfidReadService reads = new RfidReadService(rfid.readerService, rfid.machineService, device);
	final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RfidAPI(reads)).build();

	final RfidReader desk = rfid.reader("Caisse 1", "192.168.100.200", 9090);

	/** A request from this address, as Tomcat gives it, asking JSON like the frontend (axios). */
	static RequestBuilder from(String remoteAddress, MockHttpServletRequestBuilder request) {
		return request.accept(MediaType.APPLICATION_JSON).with(r -> {
			r.setRemoteAddr(remoteAddress);
			return r;
		});
	}

	@Test
	@DisplayName("No machine at the address: the address seen (normalised), no machine, cannot read, the message")
	void unknownMachine() throws Exception {
		mvc.perform(from("192.168.1.99", get("/rfid/my-machine"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.address").value("192.168.1.99"))
				.andExpect(jsonPath("$.posMachineId").isEmpty())
				.andExpect(jsonPath("$.posMachineName").isEmpty())
				.andExpect(jsonPath("$.readerName").isEmpty())
				.andExpect(jsonPath("$.canRead").value(false))
				.andExpect(jsonPath("$.message").value("No POS machine has the address 192.168.1.99: add this machine"
						+ " on the RFID POS machines page (it needs a fixed address)."));
		// The browser on the PC of the back end: Tomcat's IPv6 loopback is shown as 127.0.0.1
		mvc.perform(from("0:0:0:0:0:0:0:1", get("/rfid/my-machine"))).andExpect(jsonPath("$.address").value("127.0.0.1"));
	}

	@Test
	@DisplayName("A machine with an active reader: its name, its reader, can read; the reader is not called")
	void readyMachine() throws Exception {
		long id = rfid.machine("PC caisse 1", "192.168.1.11", desk).getId();
		mvc.perform(from("::ffff:192.168.1.11", get("/rfid/my-machine"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.address").value("192.168.1.11"))
				.andExpect(jsonPath("$.posMachineId").value(id))
				.andExpect(jsonPath("$.posMachineName").value("PC caisse 1"))
				.andExpect(jsonPath("$.readerName").value("Caisse 1"))
				.andExpect(jsonPath("$.canRead").value(true))
				.andExpect(jsonPath("$.message").isEmpty());
		assertTrue(device.calls.isEmpty());
	}

	@Test
	@DisplayName("A machine that cannot read: known, cannot read, the reason (no reader, inactive reader)")
	void notReady() throws Exception {
		rfid.machine("PC bureau", "192.168.1.20", null);
		mvc.perform(from("192.168.1.20", get("/rfid/my-machine")))
				.andExpect(jsonPath("$.posMachineName").value("PC bureau"))
				.andExpect(jsonPath("$.readerName").isEmpty())
				.andExpect(jsonPath("$.canRead").value(false))
				.andExpect(jsonPath("$.message").value("The POS machine 'PC bureau' has no RFID reader."));

		rfid.machine("PC caisse 1", "192.168.1.11", desk);
		desk.setActive(false);
		mvc.perform(from("192.168.1.11", get("/rfid/my-machine")))
				.andExpect(jsonPath("$.readerName").value("Caisse 1"))
				.andExpect(jsonPath("$.canRead").value(false))
				.andExpect(jsonPath("$.message").value("The RFID reader 'Caisse 1' of the POS machine 'PC caisse 1' is"
						+ " inactive."));
	}

	@Test
	@DisplayName("POST /rfid/read: the tags of my machine's reader; the result names the POS machine and the reader")
	void read() throws Exception {
		rfid.machine("PC caisse 1", "192.168.1.11", desk);
		device.report("192.168.100.200:9090", "E2002", -52).report("192.168.100.200:9090", "E2001", -47)
				.report("192.168.100.200:9090", "E2002", -49);
		mvc.perform(from("192.168.1.11", post("/rfid/read"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.posMachineName").value("PC caisse 1"))
				.andExpect(jsonPath("$.readerName").value("Caisse 1"))
				.andExpect(jsonPath("$.readTimeMs").value(1500))
				.andExpect(jsonPath("$.tags.length()").value(2))
				.andExpect(jsonPath("$.tags[0].epc").value("E2001"))
				.andExpect(jsonPath("$.tags[1].epc").value("E2002"))
				.andExpect(jsonPath("$.tags[1].bestRssi").value(-49.0))
				.andExpect(jsonPath("$.tags[1].seen").value(2));
	}

	@Test
	@DisplayName("POST /rfid/read refused: 404 with the address seen, 409 not ready or busy, 503 unreachable, 502 error")
	void readRefused() throws Exception {
		mvc.perform(from("0:0:0:0:0:0:0:1", post("/rfid/read"))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.error").value("No POS machine has the address 127.0.0.1: add this machine on the"
						+ " RFID POS machines page (it needs a fixed address)."))
				.andExpect(jsonPath("$.reason").value("NOT_FOUND"));

		rfid.machine("PC bureau", "192.168.1.20", null);
		mvc.perform(from("192.168.1.20", post("/rfid/read"))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("The POS machine 'PC bureau' has no RFID reader."))
				.andExpect(jsonPath("$.reason").value("NOT_READY"));

		rfid.machine("PC caisse 1", "192.168.1.11", desk);
		rfid.machine("PC caisse 2", "192.168.1.12", desk);
		CountDownLatch release = device.holdNextRead();
		CompletableFuture<?> first = CompletableFuture.runAsync(() -> reads.readForRequest("192.168.1.11"));
		device.awaitHeldRead();
		mvc.perform(from("192.168.1.12", post("/rfid/read"))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("The RFID reader 'Caisse 1' is busy with another read: try again in a"
						+ " moment."))
				.andExpect(jsonPath("$.reason").value("BUSY"));
		release.countDown();
		first.get(5, TimeUnit.SECONDS);

		device.unreachable("192.168.100.200:9090");
		mvc.perform(from("192.168.1.12", post("/rfid/read"))).andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.reason").value("UNREACHABLE"));
		RfidReader door = rfid.reader("Entrée", "192.168.100.201", 9090);
		rfid.machine("PC entrée", "192.168.1.13", door);
		device.failing("192.168.100.201:9090");
		mvc.perform(from("192.168.1.13", post("/rfid/read"))).andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.reason").value("READER_ERROR"));
	}

	@Test
	@DisplayName("Refusals: {error, reason} with the status of the reason")
	void errors() {
		assertEquals(HttpStatus.BAD_REQUEST, RfidErrors.statusOf(Reason.INVALID));
		assertEquals(HttpStatus.NOT_FOUND, RfidErrors.statusOf(Reason.NOT_FOUND));
		assertEquals(HttpStatus.CONFLICT, RfidErrors.statusOf(Reason.NOT_READY));
		assertEquals(HttpStatus.CONFLICT, RfidErrors.statusOf(Reason.BUSY));
		assertEquals(HttpStatus.BAD_GATEWAY, RfidErrors.statusOf(Reason.READER_ERROR));
		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, RfidErrors.statusOf(Reason.UNREACHABLE));
		for (Reason reason : Reason.values()) {
			assertTrue(RfidErrors.statusOf(reason) != HttpStatus.INTERNAL_SERVER_ERROR, reason.name());
		}
		java.util.Map<String, String> body = RfidErrors.answer(new RfidException(Reason.BUSY, "busy")).getBody();
		assertEquals("{error=busy, reason=BUSY}", String.valueOf(body));
	}
}
