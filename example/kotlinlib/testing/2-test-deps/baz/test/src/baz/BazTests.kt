package baz

import com.google.common.math.IntMath
import io.kotest.core.spec.style.FunSpec

class BazTests :
    FunSpec({

        test("simple") {
            BazTestUtils.bazAssertEquals(Baz.VALUE, IntMath.mean(122, 124))
        }
    })
