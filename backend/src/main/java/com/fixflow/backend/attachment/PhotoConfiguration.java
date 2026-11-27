package com.fixflow.backend.attachment;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(PhotoSettings.class)
public class PhotoConfiguration {
    @Bean("photoHttpClient") HttpClient photoHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
}
