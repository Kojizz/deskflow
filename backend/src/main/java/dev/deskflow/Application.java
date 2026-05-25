package dev.deskflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        var app = new SpringApplication(Application.class);
        if ("worker".equals(System.getenv("APP_ROLE"))) app.setAdditionalProfiles("worker");
        app.run(args);
    }
}
