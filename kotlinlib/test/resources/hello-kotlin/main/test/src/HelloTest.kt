/*
 * Copyright 2020-Present Original lefou/mill-kotlin repository contributors.
 */

package hello.tests

import hello.getHelloString
import org.junit.Test
import kotlin.test.assertEquals

class HelloTest {
    @Test fun testSuccess() {
        assertEquals("Hello, world!", getHelloString())
    }

    @Test fun testFailure() {
        assertEquals("world!", getHelloString())
    }
}
