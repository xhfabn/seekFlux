package io.seekflux.apps.agentserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.seekflux")
public class AgentServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServerApplication.class, args);
    }
}
