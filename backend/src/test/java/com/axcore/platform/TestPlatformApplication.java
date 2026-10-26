package com.axcore.platform;

import org.springframework.boot.SpringApplication;

/** 로컬 DB 없이 Testcontainers PostgreSQL로 앱을 띄운다. 실행: ./gradlew bootTestRun */
public class TestPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.from(PlatformApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
