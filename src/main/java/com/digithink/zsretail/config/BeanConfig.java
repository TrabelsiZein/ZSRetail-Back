package com.digithink.zsretail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.security.CurrentUserProvider;

import lombok.AllArgsConstructor;

@Configuration
@AllArgsConstructor
public class BeanConfig {

	@Bean(name = "BCryptPasswordEncoder")
	BCryptPasswordEncoder getBCryptPasswordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}

	@Bean
	public HttpHeaders getHttpHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	@Bean
	public CurrentUserProvider currentUserProvider() {
		return new CurrentUserProvider();
	}

}
