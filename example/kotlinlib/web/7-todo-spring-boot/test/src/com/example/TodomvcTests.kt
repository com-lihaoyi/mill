package com.example

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TodomvcTests {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun homePageLoads() {
        val response: ResponseEntity<String> = restTemplate.getForEntity("http://localhost:$port/", String::class.java)
        assertThat(response.statusCode.is2xxSuccessful).isTrue()
        assertThat(response.body).contains("<h1>todos</h1>")
    }

    @Test
    fun addNewTodoItem() {
        // Set up headers and form data for the POST request
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
        val newTodo = "title=Test+Todo"
        val entity = HttpEntity(newTodo, headers)

        // Send the POST request to add a new todo item
        val postResponse: ResponseEntity<String> = restTemplate.exchange(
            "http://localhost:$port/",
            HttpMethod.POST,
            entity,
            String::class.java,
        )
        assertThat(postResponse.statusCode.is3xxRedirection).isTrue()

        // Send a GET request to verify the new todo item was added
        val getResponse: ResponseEntity<String> = restTemplate.getForEntity("http://localhost:$port/", String::class.java)
        assertThat(getResponse.statusCode.is2xxSuccessful).isTrue()
        assertThat(getResponse.body).contains("Test Todo")
    }
}
