package com.example.dagger

import javax.inject.Inject
import kotlin.random.Random

class RandomNumberGenerator @Inject constructor() : NumberGenerator {
    override fun generate(): Int = Random.nextInt()
}
