package com.digithink.zsretail.headoffice;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.digithink.zsretail.core.config.ZsRetailApplication;

/**
 * Code cleanup step 6.5: the head office application, its own WAR (zsretail-headoffice), deployed alone in its Tomcat
 * under the context name zsretailws. It scans com.digithink.zsretail, as the single application did: in this WAR that is
 * core, erp-api, the connectors and the head office (its components, entities and repositories).
 */
@EnableAsync
@SpringBootApplication(scanBasePackages = "com.digithink.zsretail")
@AutoConfigurationPackage(basePackages = "com.digithink.zsretail")
@EnableScheduling
public class HeadOfficeApplication extends ZsRetailApplication {

	public static void main(String[] args) {
		run(HeadOfficeApplication.class, "headoffice", args);
	}

	@Override
	protected String type() {
		return "headoffice";
	}
}
