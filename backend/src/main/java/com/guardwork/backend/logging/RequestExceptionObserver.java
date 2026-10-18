package com.guardwork.backend.logging;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class RequestExceptionObserver implements WebMvcConfigurer {

    static final String EXCEPTION_ATTRIBUTE = RequestExceptionObserver.class.getName() + ".exception";

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.addFirst((request, response, handler, exception) -> {
            request.setAttribute(EXCEPTION_ATTRIBUTE, exception);
            // Observe only: existing resolvers still determine the response contract.
            return null;
        });
    }
}
