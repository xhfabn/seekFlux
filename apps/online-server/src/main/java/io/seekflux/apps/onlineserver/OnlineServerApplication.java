package io.seekflux.apps.onlineserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.seekflux")
public class OnlineServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OnlineServerApplication.class, args);
    }
}
