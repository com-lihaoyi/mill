import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AnalysisController {

  // Path to the Python binary (set in application.properties)
  @Value("${analysis.binary.path:src/main/resources/analysis/analysis.pex}")
  private String analysisBinaryPath;

  @PostMapping("/analysis")
  public ResponseEntity<String> analyzeText(@RequestBody String text) {
    try {
      // Run the Python binary with the text argument
      ProcessBuilder processBuilder = new ProcessBuilder(analysisBinaryPath, text);

      // Redirect error and output streams
      processBuilder.redirectErrorStream(true);
      Process process = processBuilder.start();

      // Read the output from the Python script
      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          output.append(line).append("\n");
        }
      }

      // Check the exit code of the process
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        return ResponseEntity.status(500).body("Error running analysis");
      }

      return ResponseEntity.ok(output.toString());

    } catch (Exception e) {
      e.printStackTrace();
      return ResponseEntity.status(500).body("Server error");
    }
  }
}

@Controller
class StaticPageController {

  // Optional controller to handle the root path ("/")
  @GetMapping("/")
  public String serveIndexPage() {
    return "index"; // Spring Boot will serve static/index.html
  }
}

// package com.example;
//
// import org.springframework.boot.SpringApplication;
// import org.springframework.boot.autoconfigure.SpringBootApplication;
// import org.springframework.web.bind.annotation.GetMapping;
// import org.springframework.web.bind.annotation.RestController;
//
// @SpringBootApplication
// public class HelloSpringBoot {
//
//  public static void main(String[] args) {
//    SpringApplication.run(HelloSpringBoot.class, args);
//  }
//
//  @RestController
//  class HelloController {
//    @GetMapping("/")
//    public String hello() {
//      return "<h1>Hello, World!</h1>";
//    }
//  }
// }
