package com.inboxiq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;

/**
 * Sessions are stored in Postgres by Spring Session JDBC (auto-configured by
 * Spring Boot from the spring-session-jdbc dependency; tables from Flyway V4),
 * so a signed-in user stays signed in across server restarts and redeploys.
 * The cookie's lifetime is set in application.yml (server.servlet.session).
 *
 * What a session holds is only Spring Security's authentication — who the
 * user is. Gmail access is a separate concern: its OAuth tokens live
 * encrypted on the mail account row and are refreshed server-side
 * (GmailCredentialProvider), so the session never depends on them.
 */
@Configuration
public class SessionConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionConfig.class);

    /**
     * Session attributes are Java-serialized. After an upgrade changes one of
     * the stored classes, an old session can no longer be read; without this,
     * every request carrying that cookie would fail with a 500. Treating an
     * unreadable attribute as absent turns it into an ordinary signed-out
     * session instead.
     */
    @Bean
    @Qualifier("springSessionConversionService")
    public ConversionService springSessionConversionService() {
        ClassLoader classLoader = getClass().getClassLoader();
        SerializingConverter serializer = new SerializingConverter();
        DeserializingConverter deserializer = new DeserializingConverter(classLoader);

        GenericConversionService conversionService = new GenericConversionService();
        conversionService.addConverter(Object.class, byte[].class, serializer::convert);
        conversionService.addConverter(byte[].class, Object.class, source -> {
            try {
                return deserializer.convert(source);
            } catch (RuntimeException e) {
                log.info("Discarding a stored session attribute that can no longer be read (likely from an older version)");
                return null;
            }
        });
        return conversionService;
    }
}
