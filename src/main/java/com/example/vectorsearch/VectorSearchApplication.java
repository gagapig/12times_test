package com.example.vectorsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.vectorsearch.task.TaskSettings;

@SpringBootApplication
@EnableConfigurationProperties(TaskSettings.class)
public class VectorSearchApplication {
    public static void main(String[] args) {
        SpringApplication.run(VectorSearchApplication.class, args);
    }
}
