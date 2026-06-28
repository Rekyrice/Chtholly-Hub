package com.chtholly.bangumi.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "bangumi.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BangumiProperties.class)
public class BangumiConfig {

    @Bean
    public RestTemplate bangumiRestTemplate(BangumiProperties properties) {
        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL);

        if (StringUtils.hasText(properties.getHttpProxy())) {
            URI proxyUri = URI.create(properties.getHttpProxy().trim());
            int port = proxyUri.getPort() > 0 ? proxyUri.getPort() : 80;
            clientBuilder.proxy(ProxySelector.of(new InetSocketAddress(proxyUri.getHost(), port)));
        } else if (properties.isUseSystemProxy()) {
            clientBuilder.proxy(ProxySelector.getDefault());
        }

        HttpClient httpClient = clientBuilder.build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(25));
        return new RestTemplate(factory);
    }
}
