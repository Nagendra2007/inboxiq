package com.inboxiq.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Spring Security's default behavior for an unauthenticated request to a
 * protected resource, when oauth2Login is configured, is to redirect to the
 * provider's login page. That's right for a top-level browser navigation
 * (the "Connect Gmail" link) but wrong for an XHR/fetch call from the SPA —
 * this returns a plain 401 JSON body instead so the frontend can show a
 * "please connect Gmail" state rather than following a redirect into HTML.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "error", "UNAUTHENTICATED",
                "message", "Sign in with Google to continue."
        ));
    }
}
