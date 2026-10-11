package com.digithink.zsretail.core.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Code cleanup step 6.5: what the two applications (StoreApplication, HeadOfficeApplication) share. The application is
 * the installation type: it activates the profile of its type (its type file, application-&lt;type&gt;.properties) and
 * gives its type as default properties, below every configuration file: spring.profiles.active (the key reads as it
 * did when application.properties gave store) and zsretail.application.type, by which
 * {@link InstallationTypeEnvironmentPostProcessor} refuses an outside file that names the other type. In Tomcat it also
 * gives the context name, by which {@link MachineFileEnvironmentPostProcessor} finds the outside file (task 9.3).
 */
public abstract class ZsRetailApplication extends SpringBootServletInitializer {

	/** The type of the application that runs, as a default property (never from a configuration file). */
	public static final String TYPE_KEY = "zsretail.application.type";

	/** The WAR's context name in Tomcat: it names the optional outside file in conf/zsretail. */
	private String contextName;

	/** store or headoffice. */
	protected abstract String type();

	/** The default properties of the application of this type. */
	public static Map<String, Object> defaults(String type) {
		Map<String, Object> defaults = new LinkedHashMap<>();
		defaults.put("spring.profiles.active", type);
		defaults.put(TYPE_KEY, type);
		return Collections.unmodifiableMap(defaults);
	}

	/** A start from main() (the IDE, the dev scripts' WarLauncher): the application of this type. */
	protected static void run(Class<? extends ZsRetailApplication> application, String type, String[] args) {
		javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((hostname, sslSession) -> true);
		SpringApplication spring = new SpringApplication(application);
		spring.setAdditionalProfiles(type);
		spring.setDefaultProperties(defaults(type));
		spring.run(args);
	}

	@Override
	public void onStartup(ServletContext servletContext) throws ServletException {
		contextName = contextName(servletContext.getContextPath());
		super.onStartup(servletContext);
	}

	@Override
	protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
		super.configure(builder);
		Map<String, Object> properties = new LinkedHashMap<>(defaults(type()));
		if (contextName != null) {
			// Default property, read by MachineFileEnvironmentPostProcessor (one WAR, one application: no system property)
			properties.put(MachineFileEnvironmentPostProcessor.CONTEXT_NAME_KEY, contextName);
		}
		return builder.profiles(type()).properties(properties).sources(getClass());
	}

	/** Tomcat's name of a context path: "" is ROOT, "/zsretailws" is zsretailws, "/a/b" is a#b. */
	public static String contextName(String contextPath) {
		if (contextPath == null || contextPath.isEmpty() || "/".equals(contextPath)) {
			return "ROOT";
		}
		return contextPath.substring(contextPath.startsWith("/") ? 1 : 0).replace('/', '#');
	}
}
