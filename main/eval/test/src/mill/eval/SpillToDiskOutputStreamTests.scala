package mill.eval

import utest._

import java.io.ByteArrayOutputStream

object SpillToDiskOutputStreamTests extends TestSuite {
  def check(totalSize: Int,
            spilled: Boolean,
            write: (Array[Byte], SpillToDiskOutputStream) => Unit) = {
    val data = Array.tabulate[Byte](totalSize)(_.toByte)
    val tmp = os.temp()
    val stdos = new SpillToDiskOutputStream(1024, tmp)
    write(data, stdos)

    assert(stdos.spilled == spilled)
    assert(stdos.size == totalSize)

    val boas = new ByteArrayOutputStream()
    stdos.writeBytesTo(boas)
    val bytes = boas.toByteArray

    assert(java.util.Arrays.equals(bytes, data))
  }

  def writeAll(data: Array[Byte], stdos: SpillToDiskOutputStream) = stdos.write(data)
  def writeRange(data: Array[Byte], stdos: SpillToDiskOutputStream) = {
    var i = 0
    while (i < data.size) {
      val delta0 = 17
      val delta = if (i + delta0 >= data.size) data.size - i else delta0
      stdos.write(data, i, delta)
      i += delta
    }
  }

  def writeIndividual(data: Array[Byte], stdos: SpillToDiskOutputStream) = {
    for(b <- data) stdos.write(b)
  }

  val tests = Tests {
    "writeAll" - {
      "unSpilled" - {
        for (totalSize <- Range(0, 1023)) check(totalSize, spilled = false, writeAll)
      }
      "spilled" - {
        for (totalSize <- Range(1024, 2048)) check(totalSize, spilled = true, writeAll)
      }
    }
    "writeRange" - {
      "unSpilled" - {
        for (totalSize <- Range(0, 1023)) check(totalSize, spilled = false, writeRange)
      }
      "spilled" - {
        for (totalSize <- Range(1024, 2048)) check(totalSize, spilled = true, writeRange)
      }
    }
    "writeIndividual" - {
      "unSpilled" - {
        for (totalSize <- Range(0, 1023)) check(totalSize, spilled = false, writeIndividual)
      }
      "spilled" - {
        for (totalSize <- Range(1024, 2048)) check(totalSize, spilled = true, writeIndividual)
      }
    }
  }
}
