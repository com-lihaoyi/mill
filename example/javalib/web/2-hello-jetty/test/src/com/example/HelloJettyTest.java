package com.example;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.HandlerList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HelloJettyTest {
  private Server server;
  private int port;

  @Before
  public void setUp() throws Exception {
    server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(0); // dynamically assign port
    server.addConnector(connector);

    HandlerList handlers = new HandlerList();
    handlers.addHandler(new HelloJetty());
    server.setHandler(handlers);

    server.start();
    port = connector.getLocalPort();
  }

  @After
  public void tearDown() throws Exception {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void testHelloJetty() throws IOException {
    URL url = new URL("http://localhost:" + port + "/");
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();

    connection.setRequestMethod("GET");
    connection.connect();

    int responseCode = connection.getResponseCode();
    assertEquals(HttpURLConnection.HTTP_OK, responseCode);
  }
}
