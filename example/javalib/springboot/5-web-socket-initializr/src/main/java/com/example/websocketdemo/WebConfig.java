package com.example.websocketdemo;

import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

@Configuration
public class WebConfig {

  @Bean
  public WebSocketHandler echoHandler() {
    return new ExampleHandler();
  }

  @Bean
  public HandlerMapping webSocketMapping(WebSocketHandler echoHandler) {
    Map<String, WebSocketHandler> map = new HashMap<>();
    map.put("/echo-websocket", new ExampleHandler());
    int order = -1; // before annotated controllers

    return new SimpleUrlHandlerMapping(map, order);
  }

  @Bean
  public WebSocketHandlerAdapter handlerAdapter() {
    return new WebSocketHandlerAdapter();
  }
}
