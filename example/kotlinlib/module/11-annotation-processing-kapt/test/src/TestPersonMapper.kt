package com.example.kapt

import org.mapstruct.Mapper

@Mapper
interface TestPersonMapper {
    fun toDto(person: TestPerson): TestPersonDto
}
