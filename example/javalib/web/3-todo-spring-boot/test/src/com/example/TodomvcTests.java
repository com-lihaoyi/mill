package com.example;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TodomvcTests {

  @LocalServerPort
  private int port;

  @Autowired
  private TestRestTemplate restTemplate;

  @Test
  void homePageLoads() {
    ResponseEntity<String> response =
        this.restTemplate.getForEntity("http://localhost:" + port + "/", String.class);
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(response.getBody()).contains("<h1>todos</h1>");
  }

  @Test
  void addNewTodoItem() {
    // Set up headers and form data for the POST request
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    String newTodo = "title=Test+Todo";
    HttpEntity<String> entity = new HttpEntity<>(newTodo, headers);

    // Send the POST request to add a new todo item
    ResponseEntity<String> postResponse = this.restTemplate.exchange(
        "http://localhost:" + port + "/", HttpMethod.POST, entity, String.class);
    assertThat(postResponse.getStatusCode().is3xxRedirection()).isTrue();

    // Send a GET request to verify the new todo item was added
    ResponseEntity<String> getResponse =
        this.restTemplate.getForEntity("http://localhost:" + port + "/", String.class);
    assertThat(getResponse.getStatusCode().is2xxSuccessful()).isTrue();
    assertThat(getResponse.getBody()).contains("Test Todo");
  }
}
