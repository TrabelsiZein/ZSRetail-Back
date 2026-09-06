package com.digithink.zsretail.erp.dynamicsnav.config;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
@ConditionalOnProperty(prefix = "erp.dynamicsnav", name = "enabled", havingValue = "true")
public class DynamicsNavConfig {

	@Bean("dynamicsNavRestTemplate")
	public RestTemplate dynamicsNavRestTemplate(DynamicsNavProperties properties) {
		CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
		credentialsProvider.setCredentials(
				AuthScope.ANY,
				new NTCredentials(
						properties.getUsername(),
						properties.getPassword(),
						null,
						properties.getDomain()));

		HttpClientBuilder clientBuilder = HttpClientBuilder.create()
				.setDefaultCredentialsProvider(credentialsProvider);

		HttpComponentsClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory();
		requestFactory.setHttpClient(clientBuilder.build());

		RestTemplate restTemplate = new RestTemplate(requestFactory);
		
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

