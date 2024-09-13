/*
 * Copyright 2020-Present Original lefou/mill-kotlin repository contributors.
 */

package hello.tests;

import hello.JavaHello;
import junit.framework.TestCase;

public class HelloTest extends TestCase {
    public void testSuccess() {
        assertEquals("Hello from Kotlin!", JavaHello.getHelloStringFromKotlin());
        assertEquals("Hello from Java!", hello.KotlinHelloKt.getHelloStringFromJava());
    }

    public void testFailure() {
        assertEquals("Hello from Java!", JavaHello.getHelloStringFromKotlin());
        assertEquals("Hello from Kotlin!", hello.KotlinHelloKt.getHelloStringFromJava());
    }
}
