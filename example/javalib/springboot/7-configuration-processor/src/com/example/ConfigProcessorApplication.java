package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableConfigurationProperties(MyProperties.class)
@RestController
public class ConfigProcessorApplication {

    private final MyProperties properties;

    public ConfigProcessorApplication(MyProperties properties) {
        this.properties = properties;
    }

    public static void main(String[] args) {
        SpringApplication.run(ConfigProcessorApplication.class, args);
    }

    @GetMapping("/")
    public String getMessage() {
        return "Config message: " + properties.getMessage();
    }
}
