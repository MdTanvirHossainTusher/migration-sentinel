package com.migrationsentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class MigrationSentinelApplication {

    public static void main(String[] args) {
        SpringApplication.run(MigrationSentinelApplication.class, args);
    }
}
