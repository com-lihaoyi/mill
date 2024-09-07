package com.etsy.sbt;

public class TestClass {

    // Utility classes w/all-static methods should not have public ctors
    private TestClass() {
    }

    public static void main(String[] args) {
        System.out.println("Hello, world!");
    }
}