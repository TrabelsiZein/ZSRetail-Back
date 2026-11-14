package com.digithink.zsretail.store.posmachine.controller;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 3.2.0, POS machines step 2, part B: the selling guard (rule 3) in front of the sales and returns endpoints of the store
 * application (never a head office: a store class). The guard itself picks the methods it restricts.
 */
@Configuration
public class PosMachineWebConfig implements WebMvcConfigurer {

	private final PosMachineSellingGuard guard;

	public PosMachineWebConfig(PosMachineSellingGuard guard) {
		this.guard = guard;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(guard).addPathPatterns("/sales-header/**", "/return-header/**", "/cash-movement/**");
	}
}
