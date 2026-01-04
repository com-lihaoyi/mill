package example.micronaut

import io.micronaut.http.HttpRequest
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

@MicronautTest // <1>
class HelloControllerTest {

    @Inject
    @field:Client("/") // <2>
    lateinit var client: HttpClient

    @Test
    fun testHello() {
        val request = HttpRequest.GET<Any>("/hello").accept(MediaType.TEXT_PLAIN) // <3>
        val body = client.toBlocking().retrieve(request)

        assertNotNull(body)
        assertEquals("Hello World", body)
    }
}
