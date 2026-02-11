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
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TodomvcIntegrationTests {

    companion object {
        private val dockerAvailable: Boolean = try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (_: Throwable) {
            false
        }

        private val postgresContainer: PostgreSQLContainer<Nothing>? = if (dockerAvailable) {
            PostgreSQLContainer<Nothing>("postgres:latest").apply {
                withDatabaseName("test")
                withUsername("test")
                withPassword("test")
                start()
            }
        } else null

        @JvmStatic
        @DynamicPropertySource
        fun postgresProperties(registry: DynamicPropertyRegistry) {
            val container = postgresContainer
            if (container != null) {
                registry.add("spring.datasource.url", container::getJdbcUrl)
                registry.add("spring.datasource.username", container::getUsername)
                registry.add("spring.datasource.password", container::getPassword)
            } else {
                registry.add("spring.datasource.url") { "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1" }
                registry.add("spring.datasource.driverClassName") { "org.h2.Driver" }
                registry.add("spring.datasource.username") { "sa" }
                registry.add("spring.datasource.password") { "" }
            }
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
