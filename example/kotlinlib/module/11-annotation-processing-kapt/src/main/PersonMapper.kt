package com.example.kapt

import org.mapstruct.Mapper
import org.mapstruct.factory.Mappers

@Mapper
interface PersonMapper {
    fun toDto(person: Person): PersonDto

    companion object {
        val INSTANCE: PersonMapper = Mappers.getMapper(PersonMapper::class.java)
    }
}
