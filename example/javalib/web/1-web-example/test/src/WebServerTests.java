package example;

import static org.junit.jupiter.api.Assertions.*;

import okhttp3.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public class WebServerTests {
  private static ConfigurableApplicationContext server;
  private static final OkHttpClient client = new OkHttpClient();

  @BeforeAll
  public static void startServer() {
    server = SpringApplication.run(WebServer.class, new String[] {});
  }

  @AfterAll
  public static void stopServer() {
    if (server != null) server.close();
  }

  @Test
  public void testReverseString() throws Exception {
    var body = RequestBody.create("helloworld", MediaType.parse("text/plain"));

    var request = new Request.Builder()
        .url("http://localhost:8080/reverse-string")
        .post(body)
        .build();

    try (var response = client.newCall(request).execute()) {
      assertTrue(response.isSuccessful());
      assertEquals("dlrowolleh", response.body().string());
    }
  }
}
