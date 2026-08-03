package io.seekflux.apps.workerrunner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableKafka
@EnableScheduling
@SpringBootApplication(scanBasePackages = "io.seekflux")
public class WorkerRunnerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkerRunnerApplication.class, args);
    }
}
