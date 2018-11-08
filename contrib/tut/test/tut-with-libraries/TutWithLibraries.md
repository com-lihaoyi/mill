```tut:silent
import cats._
import cats.arrow.FunctionK
import cats.implicits._
```

```tut
List(1, 2, 3).combineAll
λ[FunctionK[List, Option]](_.headOption)(List(1, 2 ,3))
```
