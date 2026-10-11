package com.digithink.zsretail.support;

import java.lang.reflect.AnnotatedElement;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

/** Code cleanup step 4: the installation question a class or a @Bean method carries, for the tests. */
public final class Questions {

	private Questions() {
	}

	/** True when the element carries {@code @ConditionalOnInstallation(question)}. */
	public static boolean asks(AnnotatedElement element, Installation question) {
		ConditionalOnInstallation annotation = element.getAnnotation(ConditionalOnInstallation.class);
		return annotation != null && annotation.value() == question;
	}
}
