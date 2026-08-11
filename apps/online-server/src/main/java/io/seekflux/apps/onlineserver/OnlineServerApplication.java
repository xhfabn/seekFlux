package io.seekflux.apps.onlineserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.seekflux")
@EnableScheduling
public class OnlineServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnlineServerApplication.class, args);
    }
}
