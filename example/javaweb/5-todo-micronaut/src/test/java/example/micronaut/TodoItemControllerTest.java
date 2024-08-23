package example.micronaut;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
class TodoItemControllerTest {

    @Test
    void crud(@Client("/") HttpClient httpClient, TodoItemRepository repository) {
        BlockingHttpClient client = httpClient.toBlocking();
        // create
        long count = repository.count();
        TodoItemFormData data = new TodoItemFormData();
        data.setTitle("Micronaut");
        client.exchange(HttpRequest.POST("/save", data).contentType(MediaType.APPLICATION_FORM_URLENCODED));
        assertEquals(count + 1, repository.count());

    }
}