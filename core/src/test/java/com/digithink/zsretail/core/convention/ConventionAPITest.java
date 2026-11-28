package com.digithink.zsretail.core.convention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.OncePerRequestFilter;

import com.digithink.zsretail.core.convention.controller.ConventionAPI;
import com.digithink.zsretail.core.convention.enumeration.ConventionCardStatus;
import com.digithink.zsretail.core.convention.enumeration.ConventionStatus;
import com.digithink.zsretail.core.convention.service.ConventionEvents;
import com.digithink.zsretail.core.convention.service.ConventionService;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * 3.6.0, conventions step 1, part B3: /conventions over HTTP, standalone MockMvc over the real ConventionAPI and the real
 * service (ConventionTestBed: H2; each request one transaction, rolled back on an answer of 400 and above), JSON as
 * the application writes it (dates as text). The rights (403 CONVENTION_FORBIDDEN), each refusal's status, code and
 * params, 404, 409; the bean only with convention.enabled=true (off: no endpoint, 404).
 */
class ConventionAPITest {

	private final ConventionTestBed bed = new ConventionTestBed();
	private final CurrentUserProvider current = mock(CurrentUserProvider.class);
	private final Set<String> held = new HashSet<>();
	private MockMvc mvc;

	/** Each request in a transaction of the bed's session, as the service's @Transactional. */
	private final class InTransaction extends OncePerRequestFilter {
		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			bed.session.beginTransaction();
			try {
				chain.doFilter(request, response);
			} finally {
				if (response.getStatus() < 400) {
					bed.session.getTransaction().commit();
				} else {
					bed.session.getTransaction().rollback();
				}
				bed.session.clear();
			}
		}
	}

	@BeforeEach
	void setUp() {
		ConventionAPI api = new ConventionAPI();
		ReflectionTestUtils.setField(api, "service", bed.service);
		ReflectionTestUtils.setField(api, "currentUserProvider", current);
		when(current.getCurrentUserName()).thenReturn("admin");
		when(current.currentUserHasPermission(anyString())).thenAnswer(call -> held.contains(call.getArgument(0)));
		held.addAll(Arrays.asList(ConventionAPI.READ, ConventionAPI.WRITE));
		mvc = MockMvcBuilders.standaloneSetup(api).addFilters(new InTransaction())
				.setMessageConverters(new MappingJackson2HttpMessageConverter(Jackson2ObjectMapperBuilder.json()
						.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build())) // as Spring Boot
				.build();
	}

	@AfterEach
	void close() {
		bed.close();
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static final String MIN = "{\"code\":\" min \",\"name\":\"Ministère de la Santé\",\"organisation\":\"Ministère\","
			+ "\"startDate\":\"2026-11-01\",\"endDate\":\"2027-10-31\",\"maxAmountPerMonth\":250.5}";

	private long createMin() throws Exception {
		mvc.perform(json(post("/conventions"), MIN)).andExpect(status().isOk());
		return bed.conventions.findByCode("MIN").get().getId();
	}

	private ResultActions refusal(ResultActions call, int status, String code) throws Exception {
		return call.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.error").isNotEmpty());
	}

	@Test
	@DisplayName("POST: 200, the convention as the page reads it (dates as text, the state, the cards, the settings free);"
			+ " GET /{id} the same; GET the list with its page")
	void createAndRead() throws Exception {
		mvc.perform(json(post("/conventions"), MIN)).andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("MIN"))
				.andExpect(jsonPath("$.startDate").value("2026-11-01"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.state").value("ACTIVE"))
				.andExpect(jsonPath("$.recognition").value("CARDS"))
				.andExpect(jsonPath("$.maxAmountPerMonth").value(250.5))
				.andExpect(jsonPath("$.cardDigits").value(5))
				.andExpect(jsonPath("$.origin").value("LOCAL"))
				.andExpect(jsonPath("$.cardsActive").value(0))
				.andExpect(jsonPath("$.cardSettingsFixed").value(false))
				.andExpect(jsonPath("$.createdBy").value("admin"));
		long id = bed.conventions.findByCode("MIN").get().getId();
		mvc.perform(get("/conventions/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.name")
				.value("Ministère de la Santé"));
		mvc.perform(get("/conventions").param("status", "all").param("q", "santé")).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].code").value("MIN"));
		assertEquals("CONVENTION_CREATED", bed.log.only("CONVENTION_CREATED").getCode());
	}

	@Test
	@DisplayName("The rights: the reads need read:admin-conventions, every change write:admin-conventions; 403"
			+ " CONVENTION_FORBIDDEN with the permission, nothing changed")
	void rights() throws Exception {
		long id = createMin();
		held.remove(ConventionAPI.WRITE);
		mvc.perform(get("/conventions")).andExpect(status().isOk());
		mvc.perform(get("/conventions/" + id)).andExpect(status().isOk());
		for (MockHttpServletRequestBuilder change : Arrays.asList(json(post("/conventions"), MIN.replace("min", "other")),
				json(put("/conventions/" + id), MIN), json(put("/conventions/" + id + "/status"), "{\"status\":\"SUSPENDED\"}"),
				delete("/conventions/" + id))) {
			refusal(mvc.perform(change), 403, "CONVENTION_FORBIDDEN")
					.andExpect(jsonPath("$.params.permission").value(ConventionAPI.WRITE));
		}
		held.clear();
		refusal(mvc.perform(get("/conventions")), 403, "CONVENTION_FORBIDDEN")
				.andExpect(jsonPath("$.params.permission").value(ConventionAPI.READ));
		refusal(mvc.perform(get("/conventions/" + id)), 403, "CONVENTION_FORBIDDEN");
		assertEquals(1, bed.conventions.count());
		assertEquals(ConventionStatus.ACTIVE, bed.row(id).getStatus());
	}

	@Test
	@DisplayName("Each refusal: 400 with its code and params, the change rolled back")
	void refusals() throws Exception {
		long id = createMin();
		refusal(mvc.perform(json(post("/conventions"), MIN)), 400, "CONVENTION_CODE_TAKEN")
				.andExpect(jsonPath("$.params.code").value("MIN"));
		refusal(mvc.perform(json(post("/conventions"), MIN.replace(" min ", "MIN 2"))), 400, "CONVENTION_CODE_INVALID")
				.andExpect(jsonPath("$.params.code").value("MIN 2"));
		refusal(mvc.perform(json(post("/conventions"), "{\"code\":\"X\"}")), 400, "CONVENTION_NAME_REQUIRED");
		refusal(mvc.perform(json(post("/conventions"), MIN.replace("min", "B").replace("2027-10-31", "2026-10-31"))), 400,
				"CONVENTION_END_BEFORE_START").andExpect(jsonPath("$.params.end").value("2026-10-31"));
		refusal(mvc.perform(json(post("/conventions"), MIN.replace("min", "C").replace("250.5", "0"))), 400,
				"CONVENTION_LIMIT_INVALID").andExpect(jsonPath("$.params.field").value("maxAmountPerMonth"));
		refusal(mvc.perform(json(put("/conventions/" + id), MIN.replace(" min ", "MIN-2"))), 400, "CONVENTION_CODE_FIXED")
				.andExpect(jsonPath("$.params.asked").value("MIN-2"));
		bed.card(id, "MIN-00001", ConventionCardStatus.ACTIVE);
		refusal(mvc.perform(json(put("/conventions/" + id), MIN.replace("}", ",\"cardDigits\":6}"))), 400,
				"CONVENTION_CARD_SETTINGS_FIXED").andExpect(jsonPath("$.params.field").value("cardDigits"));
		refusal(mvc.perform(json(put("/conventions/" + id + "/status"), "{\"status\":\"GONE\"}")), 400,
				"CONVENTION_STATUS_UNKNOWN");
		refusal(mvc.perform(delete("/conventions/" + id)), 400, "CONVENTION_HAS_CARDS")
				.andExpect(jsonPath("$.params.cards").value(1));
		refusal(mvc.perform(get("/conventions").param("status", "EXPIRED")), 400, "CONVENTION_STATUS_UNKNOWN");
		assertEquals(1, bed.conventions.count());
		assertEquals("Ministère de la Santé", bed.row(id).getName());
	}

	@Test
	@DisplayName("Edit, suspend, reactivate, delete: 200 each; the status and origin of a form ignored; 404 for a"
			+ " convention that is not there")
	void changes() throws Exception {
		long id = createMin();
		mvc.perform(json(put("/conventions/" + id), MIN.replace("}", ",\"status\":\"SUSPENDED\",\"origin\":\"HEAD_OFFICE\","
				+ "\"lastCardSeq\":99,\"maxUsesPerDay\":1}"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.origin").value("LOCAL"))
				.andExpect(jsonPath("$.maxUsesPerDay").value(1));
		assertEquals(0L, bed.row(id).getLastCardSeq());
		mvc.perform(json(put("/conventions/" + id + "/status"), "{\"status\":\"SUSPENDED\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("SUSPENDED"));
		mvc.perform(json(put("/conventions/" + id + "/status"), "{\"status\":\"ACTIVE\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVE"));
		mvc.perform(delete("/conventions/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("MIN"));
		assertFalse(bed.conventions.existsById(id));
		assertEquals(Arrays.asList("CONVENTION_CREATED", "CONVENTION_UPDATED", "CONVENTION_SUSPENDED",
				"CONVENTION_REACTIVATED", "CONVENTION_DELETED"), bed.log.codes());
		for (MockHttpServletRequestBuilder missing : Arrays.asList(get("/conventions/" + id),
				json(put("/conventions/" + id), MIN), json(put("/conventions/" + id + "/status"), "{\"status\":\"ACTIVE\"}"),
				delete("/conventions/" + id))) {
			refusal(mvc.perform(missing), 404, "CONVENTION_NOT_FOUND").andExpect(jsonPath("$.params.id").value(id));
		}
	}

	@Test
	@DisplayName("Decision 2, a store whose promotions are the head office's: every change 409"
			+ " CONVENTION_OWNED_BY_HEAD_OFFICE, the reads 200")
	void ownedByHeadOffice() throws Exception {
		long id = createMin();
		bed.ownedByHeadOffice = true;
		for (MockHttpServletRequestBuilder change : Arrays.asList(json(post("/conventions"), MIN.replace("min", "other")),
				json(put("/conventions/" + id), MIN), json(put("/conventions/" + id + "/status"), "{\"status\":\"SUSPENDED\"}"),
				delete("/conventions/" + id))) {
			refusal(mvc.perform(change), 409, "CONVENTION_OWNED_BY_HEAD_OFFICE");
		}
		mvc.perform(get("/conventions")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
		mvc.perform(get("/conventions/" + id)).andExpect(status().isOk());
	}

	@Test
	@DisplayName("The module's beans (the API, the service, the events) only with convention.enabled=true: off, blank or a"
			+ " wrong value, none (the address answers 404)")
	void onlyWithTheSwitch() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
				.withUserConfiguration(ConventionAPI.class, ConventionService.class, ConventionEvents.class)
				.withInitializer(context -> { // as they are: Spring injects nothing into them
					context.getBeanFactory().registerSingleton("conventionRepository", bed.conventions);
					context.getBeanFactory().registerSingleton("conventionCardRepository", bed.cards);
					context.getBeanFactory().registerSingleton("eventLog", bed.log.eventLog());
					context.getBeanFactory().registerSingleton("applicationModeService",
							mock(com.digithink.zsretail.core.config.ApplicationModeService.class));
					context.getBeanFactory().registerSingleton("currentUserProvider", current);
				});
		runner.withPropertyValues("convention.enabled=true").run(context -> {
			assertEquals(1, context.getBeansOfType(ConventionAPI.class).size());
			assertEquals(1, context.getBeansOfType(ConventionService.class).size());
			assertEquals(1, context.getBeansOfType(ConventionEvents.class).size());
		});
		for (String off : new String[] { "convention.enabled=false", "convention.enabled=", "convention.enabled=yes" }) {
			runner.withPropertyValues(off).run(context -> {
				assertEquals(0, context.getBeansOfType(ConventionAPI.class).size(), off);
				assertEquals(0, context.getBeansOfType(ConventionService.class).size(), off);
				assertEquals(0, context.getBeansOfType(ConventionEvents.class).size(), off);
			});
		}
		runner.run(context -> assertEquals(0, context.getBeansOfType(ConventionAPI.class).size(), "no line"));
	}
}
