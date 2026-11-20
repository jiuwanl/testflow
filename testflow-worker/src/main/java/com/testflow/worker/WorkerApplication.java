package com.testflow.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.testflow.persistence.mapper")
@EnableScheduling
public class WorkerApplication {
  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(WorkerApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    application.run(args);
  }
}
