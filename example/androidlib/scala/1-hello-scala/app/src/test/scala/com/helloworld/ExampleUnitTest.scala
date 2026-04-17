package com.helloworld

import com.helloworld.app.MainActivity
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  def testParseMessage(): Unit = {
    val json = """{"message": "Test Message", "version": 1}"""
    val message = MainActivity.parseMessage(json)
    assertEquals("Test Message", message)
  }
}
