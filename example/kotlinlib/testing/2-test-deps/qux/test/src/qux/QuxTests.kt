package qux

import io.kotest.core.spec.style.FunSpec
import com.google.common.base.Ascii

class QuxTests :
    FunSpec({

        test("simple") {
            baz.BazTestUtils.bazAssertEquals(Ascii.toLowerCase("XYZ"), Qux.VALUE)
        }
    })
