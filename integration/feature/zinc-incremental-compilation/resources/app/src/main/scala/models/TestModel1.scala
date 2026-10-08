package models

import java.time._

case class Foo(
    id: Long = 0L,
    gmtCreate: LocalDateTime
)
