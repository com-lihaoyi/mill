package models

import java.time.*

case class Foo(
    id: Long = 0L,
    gmtCreate: LocalDateTime
)
