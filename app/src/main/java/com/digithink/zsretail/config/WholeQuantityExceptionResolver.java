package com.digithink.zsretail.config;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import com.digithink.zsretail.utils.WholeQuantityDeserializer.NotWholeQuantity;

import lombok.extern.log4j.Log4j2;

/**
 * 2.2.1, step 5: a request whose whole-number quantity field was sent with decimals ({@link NotWholeQuantity}) gets a
 * 400 whose body is the text naming the field, like the other refusals of the API. Every other exception is left to
 * Spring exactly as before (this resolver answers null).
 */
@Component
@Log4j2
public class WholeQuantityExceptionResolver implements HandlerExceptionResolver, Ordered {

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		NotWholeQuantity refusal = find(ex);
		if (refusal == null) {
			return null;
		}
		String message = refusal.getOriginalMessage();
		log.warn("{} {}: {}", request.getMethod(), request.getRequestURI(), message);
		try {
			response.setStatus(HttpStatus.BAD_REQUEST.value());
			response.setContentType("text/plain;charset=UTF-8");
			response.getWriter().write(message);
		} catch (IOException e) {
			return null;
		}
		return new ModelAndView();
	}

	static NotWholeQuantity find(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof NotWholeQuantity) {
				return (NotWholeQuantity) cause;
			}
		}
		return null;
	}
}
