package example;
import org.junit.jupiter.api.*;
import okhttp3.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

public class WebServerTests {
  private static ConfigurableApplicationContext server;
  private static final OkHttpClient client = new OkHttpClient();

  @BeforeAll
  public static void startServer() {
    server = SpringApplication.run(WebServer.class, new String[]{});
  }

  @AfterAll
  public static void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  public void testReverseString() throws Exception {
    RequestBody body = RequestBody.create(
      "hello world",
      MediaType.parse("text/plain")
    );

    Request request = new Request.Builder()
      .url("http://localhost:8080/reverse-string")
      .post(body)
      .build();

    try (Response response = client.newCall(request).execute()) {
      assertTrue(response.isSuccessful());
      assertEquals("dlrow olleh", response.body().string());
    }
  }
}
