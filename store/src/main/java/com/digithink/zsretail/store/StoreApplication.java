package com.digithink.zsretail.store;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.digithink.zsretail.core.config.ZsRetailApplication;

/**
 * Code cleanup step 6.6: the store application (was POSMainApp), its own WAR (zsretail-store), deployed alone in its
 * Tomcat under the context name zsretailws. It scans com.digithink.zsretail, as the single application did: in this WAR
 * that is core, erp-api, the connectors and the store (its components, entities and repositories).
 */
@EnableAsync
@SpringBootApplication(scanBasePackages = "com.digithink.zsretail")
@AutoConfigurationPackage(basePackages = "com.digithink.zsretail")
@EnableScheduling
public class StoreApplication extends ZsRetailApplication {

	public static void main(String[] args) {
		run(StoreApplication.class, "store", args);
	}

	@Override
	protected String type() {
		return "store";
	}
}
