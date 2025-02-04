package com.example;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ServerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    // Test static page
    @Test
    public void shouldReturnStaticPage() {
        String response = restTemplate.getForObject("http://localhost:" + port + "/", String.class);
        assertTrue(response.contains("<title>Sentiment Analysis Tool</title>"));
    }

    // Test positive sentiment analysis
    @Test
    public void shouldReturnPositiveAnalysis() {
        String text = "this is awesome";
        String response = restTemplate.postForObject("http://localhost:" + port + "/api/analysis", text, String.class);
        assertTrue(response.contains("Positive sentiment"));
    }

    // Test negative sentiment analysis
    @Test
    public void shouldReturnNegativeAnalysis() {
        String text = "this sucks";
        String response = restTemplate.postForObject("http://localhost:" + port + "/api/analysis", text, String.class);
        assertTrue(response.contains("Negative sentiment"));
    }
}
