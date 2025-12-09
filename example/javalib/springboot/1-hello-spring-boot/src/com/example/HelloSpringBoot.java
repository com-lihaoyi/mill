package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class HelloSpringBoot {

  public static void main(String[] args) {
    SpringApplication.run(HelloSpringBoot.class, args);
  }

  @RestController
  class HelloController {
    @GetMapping("/")
    public String hello() {
      return "<h1>Hello, World!</h1>;
    }
  }
}
