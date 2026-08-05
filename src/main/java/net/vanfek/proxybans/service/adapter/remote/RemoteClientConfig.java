package net.vanfek.proxybans.service.adapter.remote;

import io.netty.channel.ChannelOption;
import net.vanfek.proxybans.service.config.ApplicationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
@ConditionalOnProperty(name = "proxybans.mode", havingValue = "remote")
class RemoteClientConfig {

    @Bean
    WebClient remoteWebClient(ApplicationProperties properties) {
        ApplicationProperties.Remote remote = properties.remote();

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) remote.connectTimeout().toMillis())
                .responseTimeout(remote.readTimeout());

        return WebClient.builder()
                .baseUrl(remote.baseUrl())
                .defaultHeader(remote.tokenHeader(), remote.apiToken())
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

}
