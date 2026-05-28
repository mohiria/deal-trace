package com.dealtrace;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.dealtrace.**.repository")
public class DealTraceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DealTraceApplication.class, args);
    }
}
