package mill.internal

import utest.*
import upickle.core.BufferedValue
import scala.collection.mutable.ArrayBuffer

object UtilTests extends TestSuite {
  val tests = Tests {
    test("parseYaml0 preserves character offsets") {
      val yaml = """key1: value1
                   |key2: 123""".stripMargin

      val parsed = Util.parseYaml0("test.yaml", yaml, yaml, mill.api.ModuleCtx.bufferedRw)

      val expected = mill.api.Result.Success(
        BufferedValue.Obj(
          ArrayBuffer(
            (BufferedValue.Str("key1", 0), BufferedValue.Str("value1", 6)),
            (BufferedValue.Str("key2", 13), BufferedValue.Num("123", -1, -1, 19))
          ),
          jsonableKeys = true,
          index = 0
        )
      )

      assert(parsed == expected)
    }

    test("parseYaml0 handles nested objects") {
      val yaml = """outer:
                   |  inner: test""".stripMargin

      val parsed = Util.parseYaml0("test.yaml", yaml, yaml, mill.api.ModuleCtx.bufferedRw)

      val expected = mill.api.Result.Success(
        BufferedValue.Obj(
          ArrayBuffer(
            (
              BufferedValue.Str("outer", 0),
              BufferedValue.Obj(
                ArrayBuffer(
                  (BufferedValue.Str("inner", 9), BufferedValue.Str("test", 16))
                ),
                jsonableKeys = true,
                index = 9
              )
            )
          ),
          jsonableKeys = true,
          index = 0
        )
      )

      assert(parsed == expected)
    }

    test("parseYaml0 handles arrays") {
      val yaml = """list:
                   |  - item1
                   |  - item2""".stripMargin

      val parsed = Util.parseYaml0("test.yaml", yaml, yaml, mill.api.ModuleCtx.bufferedRw)

      val expected = mill.api.Result.Success(
        BufferedValue.Obj(
          ArrayBuffer(
            (
              BufferedValue.Str("list", 0),
              BufferedValue.Arr(
                ArrayBuffer(
                  BufferedValue.Str("item1", 10),
                  BufferedValue.Str("item2", 20)
                ),
                index = 8
              )
            )
          ),
          jsonableKeys = true,
          index = 0
        )
      )

      assert(parsed == expected)
    }

    test("parseYaml0 handles empty document") {
      val yaml = ""
      val parsed = Util.parseYaml0("test.yaml", yaml, yaml, mill.api.ModuleCtx.bufferedRw)

      val expected = mill.api.Result.Success(
        BufferedValue.Obj(ArrayBuffer.empty, jsonableKeys = true, index = 0)
      )

      assert(parsed == expected)
    }
  }
}
