package com.example.websocketdemo;

import java.io.IOException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Example from [[<a href="https://docs.spring.io/spring-framework/reference/web/websocket/server.html">...</a>]]
 */
public class MyHandler extends TextWebSocketHandler {

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    session.sendMessage(message);
  }
}
