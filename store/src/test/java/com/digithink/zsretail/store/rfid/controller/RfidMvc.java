package com.digithink.zsretail.store.rfid.controller;

import java.lang.reflect.Field;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.store.rfid.service.RfidReadService;
import com.digithink.zsretail.store.support.FakeRfidReader;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * Test support, RFID steps 6 and 7: the three RFID controllers under Spring MVC (standalone MockMvc: the real mappings,
 * JSON and exception handlers), over the in-memory tables and the fake reader.
 */
final class RfidMvc {

	final InMemoryRfid rfid = new InMemoryRfid();
	final FakeRfidReader device = new FakeRfidReader();
	final RfidReadService reads = new RfidReadService(rfid.readerService, rfid.machineService, device);
	final MockMvc mvc;

	RfidMvc() {
		RfidReaderAPI readers = new RfidReaderAPI(reads);
		PosMachineAPI machines = new PosMachineAPI();
		service(readers, rfid.readerService);
		service(machines, rfid.machineService);
		mvc = MockMvcBuilders.standaloneSetup(new RfidAPI(reads), readers, machines, new RfidTagAPI(rfid.tagService)).build();
	}

	/** A request from this address, as Tomcat gives it, asking JSON like the frontend (axios). */
	static MockHttpServletRequestBuilder from(String remoteAddress, MockHttpServletRequestBuilder request) {
		return request.accept(MediaType.APPLICATION_JSON).with(r -> {
			r.setRemoteAddr(remoteAddress);
			return r;
		});
	}

	/** A request with this JSON body, from a machine of the store. */
	static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return from("192.168.1.50", request).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	/** _BaseController's service, which Spring injects. */
	private static void service(_BaseController<?, ?, ?> controller, Object service) {
		try {
			Field field = _BaseController.class.getDeclaredField("service");
			field.setAccessible(true);
			field.set(controller, service);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
