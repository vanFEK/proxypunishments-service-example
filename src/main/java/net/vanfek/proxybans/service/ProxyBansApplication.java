package net.vanfek.proxybans.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ProxyBansApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProxyBansApplication.class, args);
    }

}
