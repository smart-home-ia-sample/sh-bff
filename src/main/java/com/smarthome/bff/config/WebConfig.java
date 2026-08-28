package com.smarthome.bff.config;

import com.smarthome.bff.auth.AuthProperties;
import com.smarthome.bff.auth.CurrentUserArgumentResolver;
import com.smarthome.bff.mqtt.MqttProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.NonNull;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, MqttProperties.class})
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(@NonNull List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }

    /**
     * Serve the SPA build from {@code classpath:/static/} and fall back to
     * {@code index.html} for any path that isn't a real asset and isn't under
     * {@code /api} or {@code /auth} — so client-side routes (/login, /dashboard…)
     * load the app instead of 404ing.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(@NonNull String resourcePath, @NonNull Resource location)
                            throws IOException {
                        Resource requested = super.getResource(resourcePath, location);
                        if (requested != null) {
                            return requested;
                        }
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("auth/")) {
                            return null;
                        }
                        Resource index = new ClassPathResource("static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
