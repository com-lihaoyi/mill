package baz

import io.kotest.core.spec.style.FunSpec
import com.google.common.math.IntMath

class BazTests :
    FunSpec({

        test("simple") {
            BazTestUtils.bazAssertEquals(Baz.VALUE, IntMath.mean(122, 124))
        }
    })
