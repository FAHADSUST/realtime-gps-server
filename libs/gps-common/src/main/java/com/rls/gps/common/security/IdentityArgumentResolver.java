package com.rls.gps.common.security;

import com.rls.gps.common.error.ApiExceptions;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Resolves {@code @CurrentIdentity Identity} controller parameters. */
public class IdentityArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentIdentity.class)
                && Identity.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        Object attribute = webRequest.getAttribute(Identity.REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        Identity identity = attribute instanceof Identity value ? value : new Identity(null, null, null);

        CurrentIdentity annotation = parameter.getParameterAnnotation(CurrentIdentity.class);
        boolean required = annotation == null || annotation.required();
        if (required && !identity.isComplete()) {
            throw ApiExceptions.unauthorized("identity_required",
                    "Request is missing a verified caller identity; call through the gateway with a valid token");
        }
        return identity;
    }
}
