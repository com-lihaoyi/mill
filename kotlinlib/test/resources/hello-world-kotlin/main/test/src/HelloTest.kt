/*
 * Copyright 2020-Present Original lefou/mill-kotlin repository contributors.
 */

package hello.tests

import hello.getHelloString
import kotlin.test.assertEquals
import org.junit.Test

class HelloTest {
    @Test fun testSuccess() : Unit {
        assertEquals("Hello, world!", getHelloString())
    }
    @Test fun testFailure() : Unit {
        assertEquals("world!", getHelloString())
    }
}

