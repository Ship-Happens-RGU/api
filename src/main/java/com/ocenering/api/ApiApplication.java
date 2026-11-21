package com.ocenering.api;

import com.ocenering.api.auth.AuthProperties;
import com.ocenering.api.iceberg.IcebergProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({AuthProperties.class, IcebergProperties.class})
@EnableScheduling
public class ApiApplication {

    public static void main(String[] args) {
        JavaRuntimeGuard.check();

        SpringApplication.run(ApiApplication.class, args);
    }
}
