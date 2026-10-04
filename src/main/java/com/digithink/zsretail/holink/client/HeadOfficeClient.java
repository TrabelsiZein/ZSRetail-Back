package com.digithink.zsretail.holink.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.config.ConditionalOnHeadOfficeLink;
import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.dto.HeadOfficeHeartbeatDTO;
import com.digithink.zsretail.headoffice.dto.HeadOfficeHeartbeatAnswerDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberAnswerDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberEditDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMovementCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyPhoneCheckDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyPointsAdjustDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyAnswerDTO;
import com.digithink.zsretail.holink.dto.HeadOfficeCallResult;
import com.digithink.zsretail.holink.dto.LiveAnswer;
import com.digithink.zsretail.holink.dto.LoyaltyMemberPushAnswer;
import com.digithink.zsretail.holink.dto.PullAnswer;
import com.digithink.zsretail.holink.dto.SalesPushAnswer;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.holink.model.DownCursor;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.SalesUpstream;
import com.digithink.zsretail.service.GeneralSetupService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Store side of the head office link (task 1.4): calls the head office on /ho/** with the store code
 * (DEFAULT_LOCATION, read at each call) and the API key. Never throws: every outcome is a
 * {@link HeadOfficeCallResult}. Called only by the ho-link thread (heartbeat, the sales push of task 2.4 and the pull
 * of copies down of step 3). The key
 * is never logged.
 * See docs/modules/head-office.md, "Head office link".
 */
@Component
@ConditionalOnHeadOfficeLink
public class HeadOfficeClient {

	static final String STORE_CODE_HEADER = "X-Store-Code";
	static final String STORE_KEY_HEADER = "X-Store-Key";
	static final String HEARTBEAT_PATH = "/ho/heartbeat";
	static final String SALES_PATH = "/ho/sales";
	static final String DOWN_PATH = "/ho/down";
	static final String STORE_CODE_SETTING = "DEFAULT_LOCATION";

	static final int CONNECT_TIMEOUT_MS = 5_000;
	static final int READ_TIMEOUT_MS = 10_000;
	static final int LIVE_CONNECT_TIMEOUT_MS = 2_000;
	static final int LIVE_READ_TIMEOUT_MS = 3_000;
	static final String LOYALTY_PATH = "/ho/loyalty";

	static final String KEY_REFUSED = "store code or key refused by the head office";
	static final String NO_LICENSE = "the head office has no valid license";
	static final String NO_STORE_CODE = "DEFAULT_LOCATION is empty in the general setup: no call to the head office";

	/** Writes the request bodies: java.time as ISO strings (e.g. 2026-10-03T10:15:30), like the head office expects. */
	private static final ObjectMapper WIRE_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	private final RestTemplate restTemplate;

	/** Step 4: the live questions (enrol phone check, member edit), on a request thread: short timeouts. */
	private final RestTemplate liveTemplate;

	private final GeneralSetupService generalSetupService;
	private final String baseUrl;
	private final String apiKey;
	private final String appVersion;

	/** Task 3.6: what this store owns, sent with each heartbeat; null sends the version only. */
	private final ApplicationModeService ownership;

	@Autowired
	public HeadOfficeClient(GeneralSetupService generalSetupService, ApplicationModeService applicationModeService,
			@Value("${headoffice.url}") String url, @Value("${headoffice.api-key:}") String apiKey,
			@Value("${app.version:unknown}") String appVersion) {
		this(newRestTemplate(), newLiveRestTemplate(), generalSetupService, url, apiKey, appVersion,
				applicationModeService);
	}

	/** With a given RestTemplate: used by the tests (MockRestServiceServer). The heartbeat sends the version only. */
	public HeadOfficeClient(RestTemplate restTemplate, GeneralSetupService generalSetupService, String url,
			String apiKey, String appVersion) {
		this(restTemplate, generalSetupService, url, apiKey, appVersion, null);
	}

	/** With a given RestTemplate and the store's ownership: used by the tests. The live questions use it too. */
	public HeadOfficeClient(RestTemplate restTemplate, GeneralSetupService generalSetupService, String url,
			String apiKey, String appVersion, ApplicationModeService ownership) {
		this(restTemplate, restTemplate, generalSetupService, url, apiKey, appVersion, ownership);
	}

	private HeadOfficeClient(RestTemplate restTemplate, RestTemplate liveTemplate,
			GeneralSetupService generalSetupService, String url, String apiKey, String appVersion,
			ApplicationModeService ownership) {
		this.ownership = ownership;
		this.restTemplate = restTemplate;
		this.liveTemplate = liveTemplate;
		this.generalSetupService = generalSetupService;
		this.baseUrl = withoutTrailingSlash(url.trim());
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.appVersion = appVersion;
	}

	/** Own instance, not the shared RestTemplate bean: connect 5 s, read 10 s. */
	static RestTemplate newRestTemplate() {
		return newRestTemplate(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
	}

	/** Step 4: the live questions, which a user's request waits for: connect 2 s, read 3 s. */
	static RestTemplate newLiveRestTemplate() {
		return newRestTemplate(LIVE_CONNECT_TIMEOUT_MS, LIVE_READ_TIMEOUT_MS);
	}

	private static RestTemplate newRestTemplate(int connectMs, int readMs) {
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
		factory.setConnectTimeout(connectMs);
		factory.setConnectionRequestTimeout(connectMs);
		factory.setReadTimeout(readMs);
		return new RestTemplate(factory);
	}

	/** headoffice.url without its trailing slashes, e.g. http://localhost:888/zsretail/api. */
	public String getBaseUrl() {
		return baseUrl;
	}

	/** The store code sent to the head office: DEFAULT_LOCATION, trimmed; null when empty. Read at each call. */
	public String readStoreCode() {
		String storeCode = generalSetupService.findValueByCode(STORE_CODE_SETTING);
		return storeCode == null || storeCode.trim().isEmpty() ? null : storeCode.trim();
	}

	/**
	 * POST /ho/heartbeat with the application version and (task 3.6) the owner of each domain and the sales upstreams,
	 * as GET /config gives them.
	 */
	public HeadOfficeCallResult heartbeat() {
		Answer<HeadOfficeHeartbeatAnswerDTO> answer = post(HEARTBEAT_PATH, heartbeatBody(),
				HeadOfficeHeartbeatAnswerDTO.class);
		return answer.failure != null ? answer.failure
				: HeadOfficeCallResult.online(answer.body.getServerTime(), answer.body.getCanEditMembers(),
						answer.body.getCanAdjustPoints(), answer.body.getRedeemRequiresOnline(),
						answer.body.getEnrolRequiresOnline());
	}

	HeadOfficeHeartbeatDTO heartbeatBody() {
		HeadOfficeHeartbeatDTO body = new HeadOfficeHeartbeatDTO(appVersion);
		if (ownership != null) {
			Map<String, String> owners = new LinkedHashMap<>();
			for (DataDomain domain : DataDomain.values()) {
				owners.put(domain.name(), ownership.ownerOf(domain).name());
			}
			List<String> upstreams = new ArrayList<>();
			for (SalesUpstream upstream : ownership.salesUpstreams()) {
				upstreams.add(upstream.name());
			}
			body.setOwnership(owners);
			body.setSalesUpstreams(upstreams);
		}
		return body;
	}

	/**
	 * Task 2.4: POST one batch of copies of one type to /ho/sales/tickets, /returns or /sessions. Delivered (state
	 * ONLINE) with one result per document; otherwise the state and message of the heartbeat table, and no result.
	 */
	public SalesPushAnswer push(SalesCopyType type, List<?> copies) {
		Answer<SalesCopyAnswerDTO> answer = post(salesPath(type), copies, SalesCopyAnswerDTO.class);
		if (answer.failure != null) {
			return SalesPushAnswer.failed(answer.failure);
		}
		if (answer.body.getResults() == null) {
			return SalesPushAnswer.failed(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"unreadable answer from the head office (no results)"));
		}
		return SalesPushAnswer.delivered(answer.body.getResults());
	}

	static String salesPath(SalesCopyType type) {
		switch (type) {
			case TICKET:
				return SALES_PATH + "/tickets";
			case RETURN:
				return SALES_PATH + "/returns";
			default:
				return SALES_PATH + "/sessions";
		}
	}

	/**
	 * Step 3: GET one page of copies down of a domain, /ho/down/&lt;domain&gt;?limit=&amp;cursor= with the cursor of the last
	 * answer, sent back unchanged (empty for the first pull). Delivered with the page; otherwise the state and message of
	 * the heartbeat table, and no page. A page of another domain or without a cursor is unreadable.
	 */
	public PullAnswer pull(DataDomain domain, String cursor, int limit) {
		URI uri;
		try {
			// The cursor is encoded strictly (+, = and & too): the head office reads back exactly what it sent
			uri = UriComponentsBuilder.fromHttpUrl(baseUrl + DOWN_PATH + "/" + domain.name().toLowerCase(Locale.ROOT))
					.queryParam("limit", limit).queryParam("cursor", "{cursor}").encode()
					.buildAndExpand(cursor == null ? "" : cursor).toUri();
		} catch (RuntimeException e) {
			return PullAnswer.failed(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"head office call failed (" + cause(e) + ")"));
		}
		Answer<CopiesDownAnswerDTO> answer = call(HttpMethod.GET, null, uri, null, CopiesDownAnswerDTO.class);
		if (answer.failure != null) {
			return PullAnswer.failed(answer.failure);
		}
		CopiesDownAnswerDTO page = answer.body;
		if (page.getCursor() == null || page.getCursor().trim().isEmpty()
				|| page.getCursor().length() > DownCursor.CURSOR_LENGTH || !domain.name().equals(page.getDomain())) {
			return PullAnswer.failed(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"unreadable answer from the head office (no cursor, or another domain)"));
		}
		if (page.getRecords() == null) {
			page.setRecords(new ArrayList<>());
		}
		if (page.getRemoved() == null) {
			page.setRemoved(new ArrayList<>());
		}
		return PullAnswer.delivered(page);
	}

	// ─── Shared loyalty (step 4) ─────────────────────────────────

	/** Step 4: POST one batch of members enrolled at this store to /ho/loyalty/members; one result per member. */
	public LoyaltyMemberPushAnswer pushLoyaltyMembers(List<LoyaltyMemberCopyDTO> copies) {
		Answer<LoyaltyMemberAnswerDTO> answer = post(LOYALTY_PATH + "/members", copies, LoyaltyMemberAnswerDTO.class);
		if (answer.failure != null) {
			return LoyaltyMemberPushAnswer.failed(answer.failure);
		}
		if (answer.body.getResults() == null) {
			return LoyaltyMemberPushAnswer.failed(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"unreadable answer from the head office (no results)"));
		}
		return LoyaltyMemberPushAnswer.delivered(answer.body.getResults());
	}

	/** Step 4: POST one batch of loyalty movements to /ho/loyalty/movements; one result per movement, by key. */
	public SalesPushAnswer pushLoyaltyMovements(List<LoyaltyMovementCopyDTO> copies) {
		Answer<SalesCopyAnswerDTO> answer = post(LOYALTY_PATH + "/movements", copies, SalesCopyAnswerDTO.class);
		if (answer.failure != null) {
			return SalesPushAnswer.failed(answer.failure);
		}
		if (answer.body.getResults() == null) {
			return SalesPushAnswer.failed(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"unreadable answer from the head office (no results)"));
		}
		return SalesPushAnswer.delivered(answer.body.getResults());
	}

	/** Step 4, live question: the card holding this phone in the network, GET /ho/loyalty/members/by-phone. */
	public LiveAnswer<LoyaltyPhoneCheckDTO> findLoyaltyMemberByPhone(String phone) {
		try {
			URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + LOYALTY_PATH + "/members/by-phone")
					.queryParam("phone", "{phone}").encode().buildAndExpand(phone == null ? "" : phone).toUri();
			return live(HttpMethod.GET, uri, null, LoyaltyPhoneCheckDTO.class);
		} catch (RuntimeException e) {
			return LiveAnswer.notAnswered(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"head office call failed (" + cause(e) + ")"));
		}
	}

	/** Step 4, live question: a member changed at this store, PUT /ho/loyalty/members/{cardNumber}. */
	public LiveAnswer<LoyaltyMemberCopyDTO> editLoyaltyMember(String cardNumber, LoyaltyMemberEditDTO edit) {
		try {
			URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + LOYALTY_PATH + "/members/{card}").encode()
					.buildAndExpand(cardNumber).toUri();
			return live(HttpMethod.PUT, uri, edit, LoyaltyMemberCopyDTO.class);
		} catch (RuntimeException e) {
			return LiveAnswer.notAnswered(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"head office call failed (" + cause(e) + ")"));
		}
	}

	/** Step 5, live question: the member as the head office holds it now, GET /ho/loyalty/members/{cardNumber}. */
	public LiveAnswer<LoyaltyMemberCopyDTO> fetchLoyaltyMember(String cardNumber) {
		try {
			URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + LOYALTY_PATH + "/members/{card}").encode()
					.buildAndExpand(cardNumber).toUri();
			return live(HttpMethod.GET, uri, null, LoyaltyMemberCopyDTO.class);
		} catch (RuntimeException e) {
			return LiveAnswer.notAnswered(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"head office call failed (" + cause(e) + ")"));
		}
	}

	/** Step 5, live question: a manual adjustment, POST /ho/loyalty/members/{cardNumber}/adjust. */
	public LiveAnswer<LoyaltyMemberCopyDTO> adjustLoyaltyPoints(String cardNumber, LoyaltyPointsAdjustDTO request) {
		try {
			URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl + LOYALTY_PATH + "/members/{card}/adjust").encode()
					.buildAndExpand(cardNumber).toUri();
			return live(HttpMethod.POST, uri, request, LoyaltyMemberCopyDTO.class);
		} catch (RuntimeException e) {
			return LiveAnswer.notAnswered(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"head office call failed (" + cause(e) + ")"));
		}
	}

	/**
	 * A live question, on the live RestTemplate (short timeouts): the 200 body, the head office's refusal with its
	 * status and {"error"} text, or why there is no answer (401 and 402 as for the heartbeat). Never throws.
	 */
	private <T> LiveAnswer<T> live(HttpMethod method, URI uri, Object body, Class<T> answerType) {
		String storeCode;
		try {
			storeCode = readStoreCode();
		} catch (RuntimeException e) {
			return LiveAnswer.notAnswered(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					STORE_CODE_SETTING + " could not be read (" + cause(e) + ")"));
		}
		if (storeCode == null) {
			return LiveAnswer.notAnswered(HeadOfficeCallResult.failure(HeadOfficeLinkState.NOT_CONFIGURED, NO_STORE_CODE));
		}
		HttpHeaders headers = new HttpHeaders();
		if (body != null) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
		headers.set(STORE_CODE_HEADER, storeCode);
		headers.set(STORE_KEY_HEADER, apiKey);
		try {
			HttpEntity<?> entity = body == null ? new HttpEntity<>(headers)
					: new HttpEntity<>(WIRE_MAPPER.writeValueAsBytes(body), headers);
			ResponseEntity<T> response = liveTemplate.exchange(uri, method, entity, answerType);
			if (response.getStatusCodeValue() != 200 || response.getBody() == null) {
				return LiveAnswer.notAnswered(response.getStatusCodeValue() != 200
						? fromStatus(response.getStatusCodeValue())
						: HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
								"unreadable answer from the head office (empty body)"));
			}
			return LiveAnswer.ok(response.getBody());
		} catch (JsonProcessingException e) {
			return LiveAnswer.notAnswered(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"request could not be written (" + cause(e) + ")"));
		} catch (RestClientResponseException e) {
			int status = e.getRawStatusCode();
			if (status == 401 || status == 402 || status >= 500) {
				return LiveAnswer.notAnswered(fromStatus(status));
			}
			return LiveAnswer.refused(status, errorText(e.getResponseBodyAsString()));
		} catch (ResourceAccessException e) {
			return LiveAnswer.notAnswered(HeadOfficeCallResult.failure(HeadOfficeLinkState.OFFLINE,
					"head office unreachable (" + cause(e) + ")"));
		} catch (RestClientException e) {
			return LiveAnswer.notAnswered(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"unreadable answer from the head office (" + cause(e) + ")"));
		} catch (RuntimeException e) {
			return LiveAnswer.notAnswered(HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"head office call failed (" + cause(e) + ")"));
		}
	}

	/** The "error" of a {"error": "..."} body; null when there is none. */
	private static String errorText(String body) {
		try {
			JsonNode error = WIRE_MAPPER.readTree(body == null ? "" : body).get("error");
			return error == null || error.isNull() ? null : error.asText();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * POST to the head office with the store code and key; the answer body, or why there is none (the state table of
	 * docs/modules/head-office.md). Never throws.
	 */
	private <T> Answer<T> post(String path, Object body, Class<T> answerType) {
		return call(HttpMethod.POST, path, null, body, answerType);
	}

	/**
	 * A call to the head office with the store code and key: POST with a JSON body, or GET without one (body null).
	 * The address is the base URL plus {@code path}, or {@code uri} when given (already encoded). Never throws.
	 */
	private <T> Answer<T> call(HttpMethod method, String path, URI uri, Object body, Class<T> answerType) {
		String storeCode;
		try {
			storeCode = readStoreCode();
		} catch (RuntimeException e) {
			return Answer.failed(HeadOfficeLinkState.ERROR, STORE_CODE_SETTING + " could not be read (" + cause(e) + ")");
		}
		if (storeCode == null) {
			return Answer.failed(HeadOfficeLinkState.NOT_CONFIGURED, NO_STORE_CODE);
		}
		HttpHeaders headers = new HttpHeaders();
		if (method != HttpMethod.GET) {
			headers.setContentType(MediaType.APPLICATION_JSON);
		}
		headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
		headers.set(STORE_CODE_HEADER, storeCode);
		headers.set(STORE_KEY_HEADER, apiKey);
		try {
			// The body is written here (UTF-8, dates as ISO strings), whatever the RestTemplate's converters
			HttpEntity<?> entity = method == HttpMethod.GET ? new HttpEntity<>(headers)
					: new HttpEntity<>(WIRE_MAPPER.writeValueAsBytes(body), headers);
			ResponseEntity<T> response = uri != null ? restTemplate.exchange(uri, method, entity, answerType)
					: restTemplate.exchange(baseUrl + path, method, entity, answerType);
			if (response.getStatusCodeValue() != 200) {
				return new Answer<>(null, fromStatus(response.getStatusCodeValue()));
			}
			if (response.getBody() == null) {
				return Answer.failed(HeadOfficeLinkState.ERROR, "unreadable answer from the head office (empty body)");
			}
			return new Answer<>(response.getBody(), null);
		} catch (JsonProcessingException e) {
			return Answer.failed(HeadOfficeLinkState.ERROR, "request could not be written (" + cause(e) + ")");
		} catch (RestClientResponseException e) {
			return new Answer<>(null, fromStatus(e.getRawStatusCode()));
		} catch (ResourceAccessException e) {
			return Answer.failed(HeadOfficeLinkState.OFFLINE, "head office unreachable (" + cause(e) + ")");
		} catch (RestClientException e) {
			return Answer.failed(HeadOfficeLinkState.ERROR, "unreadable answer from the head office (" + cause(e) + ")");
		} catch (RuntimeException e) {
			return Answer.failed(HeadOfficeLinkState.ERROR, "head office call failed (" + cause(e) + ")");
		}
	}

	/** The body of a 200 answer, or the failure; exactly one of the two is set. */
	private static final class Answer<T> {

		final T body;
		final HeadOfficeCallResult failure;

		Answer(T body, HeadOfficeCallResult failure) {
			this.body = body;
			this.failure = failure;
		}

		static <T> Answer<T> failed(HeadOfficeLinkState state, String message) {
			return new Answer<>(null, HeadOfficeCallResult.failure(state, message));
		}
	}

	/** Any status but 200: 401 and 402 are refusals, the rest is an error. */
	private static HeadOfficeCallResult fromStatus(int status) {
		switch (status) {
			case 401:
				return HeadOfficeCallResult.failure(HeadOfficeLinkState.REFUSED, KEY_REFUSED);
			case 402:
				return HeadOfficeCallResult.failure(HeadOfficeLinkState.REFUSED, NO_LICENSE);
			default:
				return HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
						"unexpected answer from the head office: HTTP " + status);
		}
	}

	/** The most specific cause, e.g. "ConnectException: Connection refused". */
	private static String cause(Exception e) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
		return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
	}

	private static String withoutTrailingSlash(String url) {
		String result = url;
		while (result.endsWith("/")) {
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}
}
