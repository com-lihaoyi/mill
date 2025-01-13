package qux

import com.google.common.base.Ascii
import io.kotest.core.spec.style.FunSpec

class QuxTests :
    FunSpec({

        test("simple") {
            baz.BazTestUtils.bazAssertEquals(Ascii.toLowerCase("XYZ"), Qux.VALUE)
        }
    })
