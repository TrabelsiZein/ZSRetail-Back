package com.digithink.zsretail.holink.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import com.digithink.zsretail.holink.service.LoyaltyPushService;
import com.digithink.zsretail.holink.service.SalesPushSettings;
import com.digithink.zsretail.holink.service.SupplyPushService;

/**
 * 2.2.2, step 4, point 4: the default frequencies of the head office link jobs, as Spring reads them (the fallback of
 * each property in the @Value of the constructor): heartbeat 3 min, sales push 30 min, copies from the head office 30
 * min, loyalty 10 min, delivery confirmations 5 min. A frequency saved from the Jobs page wins over them
 * (LinkJobServiceTest.interval).
 */
class LinkJobDefaultsTest {

	private static List<String> values(Class<?> type) {
		List<String> values = new ArrayList<>();
		for (Constructor<?> constructor : type.getDeclaredConstructors()) {
			for (Annotation[] annotations : constructor.getParameterAnnotations()) {
				for (Annotation annotation : annotations) {
					if (annotation instanceof Value) {
						values.add(((Value) annotation).value());
					}
				}
			}
		}
		return values;
	}

	@Test
	@DisplayName("Heartbeat 180 s, sales push 1800 s, copies down 1800 s, loyalty push 600 s, delivery confirmations 300 s")
	void defaults() {
		assertEquals(true, values(HeartbeatJob.class).contains("${headoffice.heartbeat-interval-seconds:180}"));
		assertEquals(true, values(SalesPushSettings.class).contains("${headoffice.sales-push.interval-seconds:1800}"));
		assertEquals(true, values(CopiesDownJob.class).contains("${headoffice.pull.interval-seconds:1800}"));
		assertEquals(true, values(LoyaltyPushService.class).contains("${headoffice.loyalty-push.interval-seconds:600}"));
		assertEquals(true, values(SupplyPushService.class).contains("${headoffice.supply-push.interval-seconds:300}"));
		assertEquals(180, HeartbeatJob.DEFAULT_INTERVAL_SECONDS);
		assertEquals(1800, SalesPushSettings.DEFAULT_INTERVAL_SECONDS);
		assertEquals(1800, CopiesDownJob.DEFAULT_INTERVAL_SECONDS);
		assertEquals(600, LoyaltyPushService.DEFAULT_INTERVAL_SECONDS);
		assertEquals(300, SupplyPushService.DEFAULT_INTERVAL_SECONDS);
	}
}
