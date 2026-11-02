package com.mailroom.config;

import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Vite fingerprints everything under {@code /assets/} (content hash in the
 * filename), so those files can be cached forever; a new deploy simply
 * references new filenames from a freshly revalidated index.html.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Tomcat has no mapping for {@code .webmanifest}, so without this the
     * web app manifest is served as application/octet-stream and Chrome
     * declines to treat the site as installable — with no error anywhere,
     * since the file downloads perfectly well.
     */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> webmanifestMimeType() {
        return factory -> {
            MimeMappings mappings = new MimeMappings(MimeMappings.DEFAULT);
            mappings.add("webmanifest", "application/manifest+json");
            factory.setMimeMappings(mappings);
        };
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
