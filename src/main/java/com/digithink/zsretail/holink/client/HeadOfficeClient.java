package com.digithink.zsretail.holink.client;

import java.util.Collections;

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

import com.digithink.zsretail.config.ConditionalOnHeadOfficeLink;
import com.digithink.zsretail.headoffice.dto.HeadOfficeHeartbeatDTO;
import com.digithink.zsretail.headoffice.dto.HeadOfficePingDTO;
import com.digithink.zsretail.holink.dto.HeadOfficeCallResult;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.service.GeneralSetupService;

/**
 * Store side of the head office link (task 1.4): calls the head office on /ho/** with the store code
 * (DEFAULT_LOCATION, read at each call) and the API key. Never throws: every outcome is a
 * {@link HeadOfficeCallResult}. Called only by the heartbeat thread. The key is never logged.
 * See docs/modules/head-office.md, "Head office link".
 */
@Component
@ConditionalOnHeadOfficeLink
public class HeadOfficeClient {

	static final String STORE_CODE_HEADER = "X-Store-Code";
	static final String STORE_KEY_HEADER = "X-Store-Key";
	static final String HEARTBEAT_PATH = "/ho/heartbeat";
	static final String STORE_CODE_SETTING = "DEFAULT_LOCATION";

	static final int CONNECT_TIMEOUT_MS = 5_000;
	static final int READ_TIMEOUT_MS = 10_000;

	static final String KEY_REFUSED = "store code or key refused by the head office";
	static final String NO_LICENSE = "the head office has no valid license";
	static final String NO_STORE_CODE = "DEFAULT_LOCATION is empty in the general setup: no call to the head office";

	private final RestTemplate restTemplate;
	private final GeneralSetupService generalSetupService;
	private final String baseUrl;
	private final String apiKey;
	private final String appVersion;

	@Autowired
	public HeadOfficeClient(GeneralSetupService generalSetupService, @Value("${headoffice.url}") String url,
			@Value("${headoffice.api-key:}") String apiKey, @Value("${app.version:unknown}") String appVersion) {
		this(newRestTemplate(), generalSetupService, url, apiKey, appVersion);
	}

	/** With a given RestTemplate: used by the tests (MockRestServiceServer). */
	public HeadOfficeClient(RestTemplate restTemplate, GeneralSetupService generalSetupService, String url,
			String apiKey, String appVersion) {
		this.restTemplate = restTemplate;
		this.generalSetupService = generalSetupService;
		this.baseUrl = withoutTrailingSlash(url.trim());
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.appVersion = appVersion;
	}

	/** Own instance, not the shared RestTemplate bean: connect 5 s, read 10 s. */
	static RestTemplate newRestTemplate() {
		HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
		factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
		factory.setConnectionRequestTimeout(CONNECT_TIMEOUT_MS);
		factory.setReadTimeout(READ_TIMEOUT_MS);
		return new RestTemplate(factory);
	}

	/** headoffice.url without its trailing slashes, e.g. http://localhost:888/zsretail/api. */
	public String getBaseUrl() {
		return baseUrl;
	}

	/** POST /ho/heartbeat with the application version. */
	public HeadOfficeCallResult heartbeat() {
		String storeCode;
		try {
			storeCode = generalSetupService.findValueByCode(STORE_CODE_SETTING);
		} catch (RuntimeException e) {
			return HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					STORE_CODE_SETTING + " could not be read (" + cause(e) + ")");
		}
		if (storeCode == null || storeCode.trim().isEmpty()) {
			return HeadOfficeCallResult.failure(HeadOfficeLinkState.NOT_CONFIGURED, NO_STORE_CODE);
		}
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
		headers.set(STORE_CODE_HEADER, storeCode.trim());
		headers.set(STORE_KEY_HEADER, apiKey);
		try {
			ResponseEntity<HeadOfficePingDTO> response = restTemplate.exchange(baseUrl + HEARTBEAT_PATH, HttpMethod.POST,
					new HttpEntity<>(new HeadOfficeHeartbeatDTO(appVersion), headers), HeadOfficePingDTO.class);
			if (response.getStatusCodeValue() != 200) {
				return fromStatus(response.getStatusCodeValue());
			}
			if (response.getBody() == null) {
				return HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
						"unreadable answer from the head office (empty body)");
			}
			return HeadOfficeCallResult.online(response.getBody().getServerTime());
		} catch (RestClientResponseException e) {
			return fromStatus(e.getRawStatusCode());
		} catch (ResourceAccessException e) {
			return HeadOfficeCallResult.failure(HeadOfficeLinkState.OFFLINE, "head office unreachable (" + cause(e) + ")");
		} catch (RestClientException e) {
			return HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR,
					"unreadable answer from the head office (" + cause(e) + ")");
		} catch (RuntimeException e) {
			return HeadOfficeCallResult.failure(HeadOfficeLinkState.ERROR, "head office call failed (" + cause(e) + ")");
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
