package com.digithink.zsretail.connector.emtop.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.connector.emtop.EmtopConnectorDescriptor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpHttp;
import com.digithink.zsretail.erp.api.ConfiguredAddress;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
@ConditionalOnErpConnector(EmtopConnectorDescriptor.NAME)
public class DynamicsNavConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger(DynamicsNavConfig.class);

	static final String BASE_URL_KEY = "erp.emtop.base-url";

	@Bean("dynamicsNavRestTemplate")
	public RestTemplate dynamicsNavRestTemplate(DynamicsNavProperties properties) {
		// A badly written address: one clear line at the start, nothing refused (no startup check here)
		String complaint = ConfiguredAddress.complaint(BASE_URL_KEY, properties.getBaseUrl());
		if (complaint != null) {
			LOGGER.error(complaint);
		}
		RestTemplate restTemplate = new RestTemplate(ErpHttp.requestFactory(
				ErpHttp.ntlm(properties.getUsername(), properties.getPassword(), properties.getDomain()),
				properties.getConnectTimeoutSeconds(), properties.getReadTimeoutSeconds()));
		
		// Configure ObjectMapper for RestTemplate to handle LocalDate correctly
		// and exclude null fields
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
//		objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
		objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
		
		// Configure the JSON message converter with our ObjectMapper
		// Find and replace the default Jackson converter
		List<HttpMessageConverter<?>> messageConverters = restTemplate.getMessageConverters();
		for (int i = 0; i < messageConverters.size(); i++) {
			if (messageConverters.get(i) instanceof MappingJackson2HttpMessageConverter) {
				MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter();
				jsonConverter.setObjectMapper(objectMapper);
				messageConverters.set(i, jsonConverter);
				break;
			}
		}
		
		return restTemplate;
	}
}

