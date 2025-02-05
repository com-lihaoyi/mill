package com.example.dagger

import dagger.Binds
import dagger.Module
import javax.inject.Singleton

@Module
interface NumberTestModule {

    @Binds
    @Singleton
    fun bindConstant42NumberGenerator(constant42NumberGenerator: ConstantNumberGenerator): NumberGenerator
}
