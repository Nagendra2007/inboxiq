package com.inboxiq.security;

import com.inboxiq.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Security posture for InboxIQ, in one place:
 *
 *  - Authentication is 100% delegated to Google via OAuth2 login
 *    (authorization_code + OIDC). InboxIQ never sees or stores a password.
 *  - The session cookie (INBOXIQ_SESSION) is HttpOnly + SameSite=Lax, so
 *    the token itself never touches frontend JS.
 *  - CSRF protection is ON for all state-changing requests, using the
 *    double-submit cookie pattern (XSRF-TOKEN cookie readable by JS,
 *    echoed back as the X-XSRF-TOKEN header) so the SPA can call mutating
 *    endpoints without a server-rendered form.
 *  - Every {@code /api/**} request requires authentication; unauthenticated
 *    API calls get a 401 JSON body (RestAuthenticationEntryPoint) instead of
 *    a redirect into Google's login page, which is only appropriate for a
 *    top-level browser navigation.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Applies to the SPA shell when the backend serves the built frontend
     * (single-service deployment). 'unsafe-inline' styles are needed because
     * sanitized email HTML keeps inline style attributes; scripts stay
     * 'self'-only, so injected markup can never execute.
     */
    private static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
            "font-src 'self' data: https://fonts.gstatic.com",
            "img-src 'self' data: https:",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "frame-ancestors 'none'",
            "form-action 'self'");

    /** Client-side routes of the React app; see SpaForwardController. */
    static final String[] SPA_ROUTES = {"/", "/login", "/inbox", "/dashboard", "/action-items", "/settings"};

    private final AppProperties appProperties;
    private final OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    public SecurityConfig(AppProperties appProperties,
                           OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler,
                           OAuth2LoginFailureHandler oAuth2LoginFailureHandler,
                           RestAuthenticationEntryPoint restAuthenticationEntryPoint) {
        this.appProperties = appProperties;
        this.oAuth2LoginSuccessHandler = oAuth2LoginSuccessHandler;
        this.oAuth2LoginFailureHandler = oAuth2LoginFailureHandler;
        this.restAuthenticationEntryPoint = restAuthenticationEntryPoint;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                     ClientRegistrationRepository clientRegistrationRepository) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieName("XSRF-TOKEN");
        csrfTokenRepository.setHeaderName("X-XSRF-TOKEN");
        // Must track the session cookie's SameSite/Secure settings exactly
        // (see app.security.cookie-same-site / cookie-secure): if this cookie
        // is left on defaults while the session cookie is switched to
        // SameSite=None for a cross-domain deployment, CSRF-protected
        // requests (logout, sync, send, etc.) start failing with 403s even
        // though the user is still logged in.
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie
                .sameSite(appProperties.getSecurity().getCookieSameSite())
                .secure(appProperties.getSecurity().isCookieSecure()));

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf
                    .csrfTokenRepository(csrfTokenRepository)
                    .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                    // Nothing today needs a CSRF exemption: sync/analysis are
                    // user-initiated POSTs from the authenticated SPA, and Gmail
                    // notifications (if enabled later) would arrive on their own
                    // unauthenticated endpoint anyway, never inside /api/**.
            )
            .headers(headers -> headers
                    .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                    .referrerPolicy(referrer -> referrer
                            .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
            )
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/oauth2/**", "/login/**", "/actuator/health", "/error").permitAll()
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/api/**").authenticated()
                    // Static SPA bundle (present only when the frontend is
                    // built into the jar — see the root Dockerfile).
                    .requestMatchers(HttpMethod.GET, SPA_ROUTES).permitAll()
                    .requestMatchers(HttpMethod.GET, "/index.html", "/assets/**", "/favicon.svg", "/robots.txt").permitAll()
                    .anyRequest().denyAll()
            )
            .exceptionHandling(ex -> ex
                    .defaultAuthenticationEntryPointFor(restAuthenticationEntryPoint,
                            request -> request.getRequestURI().startsWith("/api/"))
            )
            .oauth2Login(oauth2 -> oauth2
                    // Points at the SPA's own sign-in route. Without this,
                    // Spring Security generates its own HTML page at /login,
                    // which shadows the React route whenever /login is loaded
                    // from the server (a page refresh, or an OAuth error redirect).
                    .loginPage("/login")
                    .authorizationEndpoint(endpoint -> endpoint
                            .authorizationRequestResolver(authorizationRequestResolver(clientRegistrationRepository)))
                    .successHandler(oAuth2LoginSuccessHandler)
                    .failureHandler(oAuth2LoginFailureHandler)
            )
            .logout(logout -> logout
                    .logoutUrl("/api/auth/logout")
                    .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204))
                    .deleteCookies(appProperties.getSecurity().getSessionCookieName(), "XSRF-TOKEN")
                    .invalidateHttpSession(true)
            );

        return http.build();
    }

    /**
     * Adds {@code access_type=offline&prompt=consent} to the Google
     * authorization request. Without this, Google only ever issues a
     * refresh token on the very first consent — and never again, even on a
     * fresh login — which would silently break background sync/reply-sending
     * once the initial access token expires. This is also configurable
     * directly in application.yml; kept here as a resolver in case scopes
     * ever need to be requested incrementally per-feature in the future.
     */
    private OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, "/oauth2/authorization");
        resolver.setAuthorizationRequestCustomizer(builder -> builder.additionalParameters(params -> {
            Map<String, Object> extra = new HashMap<>();
            extra.put("access_type", "offline");
            extra.put("prompt", "consent");
            params.putAll(extra);
        }));
        return resolver;
    }

    /**
     * Spring Security's default CSRF handler wraps the token value with a
     * per-request BREACH-protection mask and expects it to be read back out
     * via a request attribute rendered into HTML — which never happens in a
     * pure JSON SPA. This handler still applies that same BREACH-safe
     * masking when writing the XSRF-TOKEN cookie (so the value is not
     * trivially guessable), but resolves an *incoming* request's token
     * straight from the X-XSRF-TOKEN header, which is exactly what the
     * frontend's axios/fetch client sends. This is the pattern Spring
     * Security's own reference docs recommend for SPA + cookie CSRF.
     */
    private static final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {
        private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
            this.delegate.handle(request, response, csrfToken);
            // The token is deferred by default and only materialized when a
            // protected (mutating) request needs it — so no GET would ever
            // write the XSRF-TOKEN cookie, and the first POST after sign-in
            // would always fail with 403. Loading it here issues the cookie
            // on the very first request (typically GET /api/auth/me).
            csrfToken.get();
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
            // Header: the SPA echoes the raw cookie value. Otherwise (a form
            // field) the value is the BREACH-masked token, so unmask it —
            // Spring Security's reference SPA handler does the same.
            String headerValue = request.getHeader(csrfToken.getHeaderName());
            return StringUtils.hasText(headerValue)
                    ? super.resolveCsrfTokenValue(request, csrfToken)
                    : this.delegate.resolveCsrfTokenValue(request, csrfToken);
        }
    }

    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Origins never carry a trailing slash in the browser's Origin header,
        // so tolerate "https://app.example.com/" in configuration.
        configuration.setAllowedOrigins(
                Arrays.stream(appProperties.getSecurity().getCorsAllowedOrigins().split(","))
                        .map(String::trim)
                        .map(origin -> origin.replaceAll("/+$", ""))
                        .filter(origin -> !origin.isEmpty())
                        .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
