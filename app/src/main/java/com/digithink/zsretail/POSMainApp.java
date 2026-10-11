package com.digithink.zsretail;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.digithink.zsretail.core.config.ZsRetailApplication;

/**
 * The store application until code cleanup step 6.6 (StoreApplication then): since step 6.5 the head office is its own
 * application (HeadOfficeApplication).
 */
@EnableAsync
@SpringBootApplication
@EnableScheduling
public class POSMainApp extends ZsRetailApplication {

	public static void main(String[] args) {
		run(POSMainApp.class, "store", args);
	}

	@Override
	protected String type() {
		return "store";
	}
}
