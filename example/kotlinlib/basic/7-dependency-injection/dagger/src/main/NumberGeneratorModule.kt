package com.example.dagger

import dagger.Binds
import dagger.Module
import javax.inject.Singleton

@Module
interface NumberGeneratorModule {

    @Binds
    @Singleton
    fun bindNumberGenerator(randomNumberGenerator: RandomNumberGenerator): NumberGenerator
}
