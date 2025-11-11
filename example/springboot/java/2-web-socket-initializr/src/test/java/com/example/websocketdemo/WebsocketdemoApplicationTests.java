package com.example.websocketdemo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebsocketdemoApplicationTests {

  WebSocketClient client;

  @LocalServerPort
  private int port;

  @BeforeEach
  void setUp(ApplicationContext context) {

    client = new ReactorNettyWebSocketClient();
  }

  @Test
  void contextLoads() {}

  @Test
  void getMessage() throws Exception {
    AtomicReference<String> received = new AtomicReference<>();
    String messageToSend = "Hello World!";
    String expectedMessage = "Echo " + messageToSend;
    URI url = new URI("ws://localhost:" + port + "/echo-websocket");

    // Connect, send one message, await exactly one reply, and complete.
    client
        .execute(url, session -> session
            .send(Mono.just(session.textMessage(messageToSend)))
            .thenMany(session
                .receive()
                .map(WebSocketMessage::getPayloadAsText)
                .take(1) // complete after first message
                .doOnNext(received::set))
            .then())
        .block(Duration.ofSeconds(5));

    assertEquals(expectedMessage, received.get(), "WebSocket should echo the sent message");
  }
}
