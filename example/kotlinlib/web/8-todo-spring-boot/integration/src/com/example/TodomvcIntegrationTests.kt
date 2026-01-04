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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TodomvcIntegrationTests {

    companion object {
        @Container
        val postgresContainer = PostgreSQLContainer<Nothing>("postgres:latest").apply {
            withDatabaseName("test")
            withUsername("test")
            withPassword("test")
        }

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgresContainer::getJdbcUrl)
            registry.add("spring.datasource.username", postgresContainer::getUsername)
            registry.add("spring.datasource.password", postgresContainer::getPassword)
        }
    }

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun homePageLoads() {
        val response = restTemplate.getForEntity("http://localhost:$port/", String::class.java)
        assertThat(response.statusCode.is2xxSuccessful).isTrue
        assertThat(response.body).contains("<h1>todos</h1>")
    }

    @Test
    fun addNewTodoItem() {
        // Set up headers and form data for the POST request
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
        }
        val newTodo = "title=Test+Todo"
        val entity = HttpEntity(newTodo, headers)

        // Send the POST request to add a new todo item
        val postResponse = restTemplate.exchange(
            "http://localhost:$port/",
            HttpMethod.POST,
            entity,
            String::class.java,
        )
        assertThat(postResponse.statusCode.is3xxRedirection).isTrue

        // Send a GET request to verify the new todo item was added
        val getResponse = restTemplate.getForEntity("http://localhost:$port/", String::class.java)
        assertThat(getResponse.statusCode.is2xxSuccessful).isTrue
        assertThat(getResponse.body).contains("Test Todo")
    }
}
