package example.micronaut;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

@MicronautTest
class LearnJsonTest {

  @Test
  void learnJsonAvailable(@Client("/") HttpClient httpClient) {
    BlockingHttpClient client = httpClient.toBlocking();
    assertDoesNotThrow(() -> client.exchange("/learn.json"));
  }
}
