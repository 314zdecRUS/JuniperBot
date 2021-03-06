/*
 * This file is part of JuniperBot.
 *
 * JuniperBot is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * JuniperBot is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with JuniperBot. If not, see <http://www.gnu.org/licenses/>.
 */
package ru.juniperbot.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityRequestMatcherProviderAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportResource;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.juniperbot.api.common.ApiRequestLoggingFilter;
import ru.juniperbot.api.common.AtomFeedArgumentResolver;
import ru.juniperbot.common.configuration.CommonConfiguration;
import ru.juniperbot.common.support.ModuleMessageSourceImpl;

import java.util.List;

@Import({CommonConfiguration.class})
@ImportResource("classpath:security-context.xml")
@SpringBootApplication(exclude = {
        SecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        SecurityRequestMatcherProviderAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
})
public class JuniperApiApplication implements WebMvcConfigurer {

    public static void main(String[] args) {
        SpringApplication.run(JuniperApiApplication.class, args);
    }

    @Bean
    public ModuleMessageSourceImpl webMessages() {
        ModuleMessageSourceImpl source = new ModuleMessageSourceImpl();
        source.setBasename("web-jbmessages");
        return source;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(new AtomFeedArgumentResolver());
    }

    @Bean
    public ApiRequestLoggingFilter requestLoggingFilter() {
        ApiRequestLoggingFilter filter = new ApiRequestLoggingFilter();
        filter.setBeforeMessagePrefix("Before Request ");
        filter.setAfterMessagePrefix("After Request ");
        filter.setBeforeMessageSuffix("");
        filter.setAfterMessageSuffix("");
        filter.setMaxPayloadLength(10000);
        filter.setIncludeClientInfo(true);
        filter.setIncludeQueryString(true);
        filter.setIncludePayload(true);
        return filter;
    }
}
