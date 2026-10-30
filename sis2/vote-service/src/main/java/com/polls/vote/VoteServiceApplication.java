package com.polls.vote;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {"com.polls.vote", "com.polls.common"})
@EnableScheduling
public class VoteServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(VoteServiceApplication.class, args);
    }
}
