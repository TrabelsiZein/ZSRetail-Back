package com.digithink.zsretail.config;

import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * Configuration step C1: one INFO line once the application is up, saying what this installation is and where it runs
 * (type, database, each owner, where the sales go, the NAV address when the connector is on, the outside file when one
 * was loaded). Never a password or a key.
 */
@Component
public class InstallationSummary {

	private static final Logger log = LoggerFactory.getLogger(InstallationSummary.class);
	private static final Pattern DATABASE_NAME = Pattern.compile("databaseName=([^;]+)", Pattern.CASE_INSENSITIVE);

	@Autowired
	private Environment environment;

	@EventListener(ApplicationReadyEvent.class)
	public void logSummary() {
		log.info(summary(environment, NodeOwnership.resolve(environment)));
	}

	static String summary(Environment env, NodeOwnership ownership) {
		StringJoiner line = new StringJoiner(", ", "Installation: ", "");
		line.add("type " + ownership.getNodeType());
		line.add("database " + databaseName(env.getProperty("spring.datasource.url")));
		for (DataDomain domain : DataDomain.values()) {
			line.add(domain.name().toLowerCase() + " " + ownership.ownerOf(domain));
		}
		line.add("sales to " + (ownership.getSalesUpstreams().isEmpty() ? "nowhere" : ownership.getSalesUpstreams()));
		if (ownership.isErpCatalogueOnly()) {
			line.add("only the catalogue from the ERP (customers and supply kept here)");
		}
		if (NodeOwnership.isHeadOfficeWithoutStockSet(env)) {
			line.add("head office stock off");
		}
		if (Boolean.parseBoolean(env.getProperty("erp.dynamicsnav.enabled", "false").trim())) {
			line.add("NAV " + env.getProperty("erp.dynamicsnav.base-url", "(no address)"));
		}
		String outsideFile = env.getProperty(MachineFileEnvironmentPostProcessor.LOADED_KEY);
		line.add(outsideFile == null ? "no outside file" : "outside file " + outsideFile);
		return line.toString();
	}

	/** The databaseName of a SQL Server JDBC URL; the URL without its parameters when it names none. */
	static String databaseName(String url) {
		if (url == null || url.trim().isEmpty()) {
			return "(none)";
		}
		Matcher matcher = DATABASE_NAME.matcher(url);
		return matcher.find() ? matcher.group(1).trim() : url.split(";")[0];
	}
}
