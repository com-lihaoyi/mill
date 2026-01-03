package example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

@SpringBootApplication
@RestController
public class WebServer {
  public static void main(String[] args) {
    String port = System.getenv().getOrDefault("PORT", "8080");
    System.setProperty("server.port", port);
    SpringApplication.run(WebServer.class, args);
  }

  @PostMapping("/reverse-string")
  public String reverseString(@RequestBody String body) {
    return new StringBuilder(body).reverse().toString();
  }
}
