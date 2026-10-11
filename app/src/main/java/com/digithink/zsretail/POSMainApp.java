package com.digithink.zsretail;

import java.util.Collections;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.digithink.zsretail.config.MachineFileEnvironmentPostProcessor;

@EnableAsync
@SpringBootApplication
@EnableScheduling
public class POSMainApp extends SpringBootServletInitializer {

	/** The WAR's context name in Tomcat (task 9.3): it names the optional outside file in conf/zsretail. */
	private String contextName;

	public static void main(String[] args) {
		javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((hostname, sslSession) -> true);
		SpringApplication.run(POSMainApp.class, args);
	}

	@Override
	public void onStartup(ServletContext servletContext) throws ServletException {
		contextName = contextName(servletContext.getContextPath());
		super.onStartup(servletContext);
	}

	@Override
	protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
		super.configure(builder);
		if (contextName != null) {
			// Default property, read by MachineFileEnvironmentPostProcessor (one WAR, one application: no system property)
			builder.properties(Collections.singletonMap(MachineFileEnvironmentPostProcessor.CONTEXT_NAME_KEY, contextName));
		}
		return builder.sources(POSMainApp.class);
	}

	/** Tomcat's name of a context path: "" is ROOT, "/zsretailws" is zsretailws, "/a/b" is a#b. */
	static String contextName(String contextPath) {
		if (contextPath == null || contextPath.isEmpty() || "/".equals(contextPath)) {
			return "ROOT";
		}
		return contextPath.substring(contextPath.startsWith("/") ? 1 : 0).replace('/', '#');
	}
}
