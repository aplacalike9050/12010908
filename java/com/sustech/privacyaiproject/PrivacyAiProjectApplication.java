package com.sustech.privacyaiproject;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 隐私网关 Spring Boot 启动类。
 */
@EnableAsync
@SpringBootApplication
public class PrivacyAiProjectApplication {

    public static void main(String[] args) {


        SpringApplication.run(PrivacyAiProjectApplication.class, args);
    }

}
/*
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "10900");
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "10900");

        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");
*/