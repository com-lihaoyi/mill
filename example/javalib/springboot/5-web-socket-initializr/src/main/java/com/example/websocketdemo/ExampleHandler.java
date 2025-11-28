package com.example.websocketdemo;

import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Example adapted from [[<a href="https://docs.spring.io/spring-framework/reference/web/webflux-websocket.html">Spring Boot Webflux Websocket</a>]]
 */
public class ExampleHandler implements WebSocketHandler {

  @Override
  public Mono<Void> handle(WebSocketSession session) {

    Flux<WebSocketMessage> output =
        session.receive().map(value -> session.textMessage("Echo " + value.getPayloadAsText()));

    return session.send(output);
  }
}
