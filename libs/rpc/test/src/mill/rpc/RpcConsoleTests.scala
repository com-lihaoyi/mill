package mill.rpc

import utest.*

import java.io.OutputStream
import java.nio.charset.StandardCharsets

object RpcConsoleTests extends TestSuite {
  val tests: Tests = Tests {

    test("asStream") {
      test("asciiPassthrough") {
        val (console, output) = makeConsole()
        val stream = console.asStream
        stream.write("hello world".getBytes(StandardCharsets.UTF_8))
        stream.flush()
        assert(output.toString == "hello world")
      }

      test("splitMultibyteAcrossWrites") {
        // "┌" = 0xE2 0x94 0x8C (3-byte UTF-8)
        // Simulate splitting this character across two writes
        val (console, output) = makeConsole()
        val stream = console.asStream

        val fullBytes = "┌".getBytes(StandardCharsets.UTF_8)
        assert(fullBytes.length == 3)

        // Write first 2 bytes (incomplete)
        stream.write(fullBytes, 0, 2)
        // Write remaining byte
        stream.write(fullBytes, 2, 1)
        stream.flush()

        assert(output.toString == "┌")
      }

      test("split2byteCharAcrossWrites") {
        // "ö" = 0xC3 0xB6 (2-byte UTF-8)
        val (console, output) = makeConsole()
        val stream = console.asStream

        val fullBytes = "ö".getBytes(StandardCharsets.UTF_8)
        stream.write(fullBytes, 0, 1)
        stream.write(fullBytes, 1, 1)
        stream.flush()

        assert(output.toString == "ö")
      }

      test("split4byteCharAcrossWrites") {
        // "𝄞" (U+1D11E) = 4 bytes
        val (console, output) = makeConsole()
        val stream = console.asStream

        val fullBytes = "𝄞".getBytes(StandardCharsets.UTF_8)
        assert(fullBytes.length == 4)

        // Split after first byte
        stream.write(fullBytes, 0, 1)
        stream.write(fullBytes, 1, 3)
        stream.flush()

        assert(output.toString == "𝄞")
      }

      test("mixedAsciiAndMultibyte") {
        val (console, output) = makeConsole()
        val stream = console.asStream

        val text = "hello┌world"
        val bytes = text.getBytes(StandardCharsets.UTF_8)
        // "hello" = 5 bytes, "┌" = 3 bytes (E2 94 8C), "world" = 5 bytes
        // Split at byte 6 (in middle of "┌")
        stream.write(bytes, 0, 6) // "hello" + first byte of "┌"
        stream.write(bytes, 6, bytes.length - 6) // rest of "┌" + "world"
        stream.flush()

        assert(output.toString == text)
      }

      test("126byteBoundarySplit") {
        // Simulate ProxyStream's 126-byte packet boundary splitting a 3-byte char
        val (console, output) = makeConsole()
        val stream = console.asStream

        // Fill 125 bytes of ASCII then a 3-byte character
        val prefix = "x" * 125
        val text = prefix + "┌"
        val bytes = text.getBytes(StandardCharsets.UTF_8)
        assert(bytes.length == 128)

        // First chunk: 126 bytes (125 ASCII + first byte of "┌")
        stream.write(bytes, 0, 126)
        // Second chunk: remaining 2 bytes of "┌"
        stream.write(bytes, 126, 2)
        stream.flush()

        assert(output.toString == text)
      }

      test("singleByteWrites") {
        // Test write(int) with multi-byte character
        val (console, output) = makeConsole()
        val stream = console.asStream

        val bytes = "┌".getBytes(StandardCharsets.UTF_8)
        bytes.foreach(b => stream.write(b & 0xff))
        stream.flush()

        assert(output.toString == "┌")
      }

      test("1024ByteBoundarySplit") {
        // Simulate InputPumper's 1024-byte buffer splitting a multi-byte char
        val (console, output) = makeConsole()
        val stream = console.asStream

        // Generate string with box-drawing chars that crosses 1024 boundary
        val boxLine = "┌──────────┐\n" // 14 chars, but more bytes in UTF-8
        val builder = new StringBuilder
        while (builder.toString.getBytes(StandardCharsets.UTF_8).length < 1030) {
          builder.append(boxLine)
        }
        val text = builder.toString
        val bytes = text.getBytes(StandardCharsets.UTF_8)

        // Write in 1024-byte chunks (like InputPumper does)
        var offset = 0
        while (offset < bytes.length) {
          val len = Math.min(1024, bytes.length - offset)
          stream.write(bytes, offset, len)
          offset += len
        }
        stream.flush()

        assert(output.toString == text)
      }

      test("exactIssueReproduction") {
        // Exact reproduction from https://github.com/com-lihaoyi/mill/issues/6891
        // "─" (U+2500, BOX DRAWINGS LIGHT HORIZONTAL) is 3 bytes: E2 94 80
        // 72 repetitions = 216 bytes per line, plus "\n" = 217 bytes per line
        // 20 lines = 4340 bytes total
        //
        // InputPumper reads 1024 bytes. ProxyStream.Output splits each 1024-byte
        // write into packets: 8 × 126 bytes + 1 × 16 bytes.
        // The 16-byte remainder contains 5 complete chars (15 bytes) + 1 incomplete
        // byte, causing corruption when naively converted to a UTF-8 string.
        val (console, output) = makeConsole()
        val stream = console.asStream

        val line = "─".repeat(72)
        val text = (1 to 20).map(_ => line).mkString("\n") + "\n"
        val bytes = text.getBytes(StandardCharsets.UTF_8)

        // Simulate the actual pipeline: InputPumper writes 1024-byte chunks,
        // ProxyStream.Output splits each into 126-byte packets
        var inputOffset = 0
        while (inputOffset < bytes.length) {
          val inputLen = Math.min(1024, bytes.length - inputOffset)
          // For each InputPumper chunk, simulate ProxyStream packet splitting
          var packetOffset = 0
          while (packetOffset < inputLen) {
            val packetLen = Math.min(126, inputLen - packetOffset)
            stream.write(bytes, inputOffset + packetOffset, packetLen)
            packetOffset += packetLen
          }
          inputOffset += inputLen
        }
        stream.flush()

        val result = output.toString
        assert(result == text)
        assert(!result.contains("\ufffd"))
      }

      test("nonMultipleOf3Split") {
        // "─" (U+2500) is 3 bytes: E2 94 80
        // When split at a non-multiple-of-3 boundary, the naive approach corrupts
        // but asStream must handle it correctly.
        val (console, output) = makeConsole()
        val stream = console.asStream

        val text = "─".repeat(100) // 300 bytes
        val bytes = text.getBytes(StandardCharsets.UTF_8)
        assert(bytes.length == 300)

        // Split at byte 100 (not a multiple of 3 → splits a "─" character)
        val splitAt = 100
        stream.write(bytes, 0, splitAt)
        stream.write(bytes, splitAt, bytes.length - splitAt)
        stream.flush()

        assert(output.toString == text)
        assert(!output.toString.contains("\ufffd"))
      }
    }
  }

  private def makeConsole(): (RpcConsole, StringBuilder) = {
    val output = new StringBuilder
    val console = new RpcConsole {
      def print(s: String): Unit = output.append(s)
      def flush(): Unit = ()
    }
    (console, output)
  }
}
