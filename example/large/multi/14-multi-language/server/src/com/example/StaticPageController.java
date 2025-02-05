package com.example;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class StaticPageController {

  @GetMapping("/")
  public ResponseEntity<String> serveIndexPage() {
    String indexHtml = readResource("static/index.html");

    if (indexHtml == null) {
      return ResponseEntity.status(404).body("Resource not found");
    }

    return ResponseEntity.ok()
        .header("Content-Type", "text/html; charset=UTF-8")
        .body(indexHtml);
  }

  private static String readResource(String resourceName) {
    try {
      return new String(
          StaticPageController.class
              .getClassLoader()
              .getResourceAsStream(resourceName)
              .readAllBytes(),
          StandardCharsets.UTF_8);
    } catch (IOException | NullPointerException e) {
      e.printStackTrace();
      return null;
    }
  }
}

// package com.example;
//
// import org.springframework.stereotype.Controller;
// import org.springframework.web.bind.annotation.GetMapping;
//
// @Controller
// public class StaticPageController {
//
//    @GetMapping("/")
//    public String serveIndexPage() {
//        return "index";  // Will serve static/index.html
//    }
// }
