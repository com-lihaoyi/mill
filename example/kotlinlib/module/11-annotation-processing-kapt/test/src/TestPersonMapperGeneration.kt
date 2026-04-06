package com.example.kapt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mapstruct.factory.Mappers

class TestPersonMapperGeneration {

    @Test
    fun generatedMapperWorks() {
        val mapper = Mappers.getMapper(TestPersonMapper::class.java)
        assertEquals("Test Mill", mapper.toDto(TestPerson("Test Mill")).name)
    }
}
