package mill.internal

import utest.*

import java.util.concurrent.Executors
import scala.concurrent.*
import scala.concurrent.ExecutionContext.Implicits.*
import scala.concurrent.duration.Duration.*

object PipeStreamsTests extends TestSuite {
  val tests = Tests {
    test("hello") { // Single write and read works
      val pipe = new PipeStreams()
      val data = Array[Byte](1, 2, 3, 4, 5, 0, 3)
      assert(data.length < pipe.bufferSize)

      pipe.output.write(data)

      val out = new Array[Byte](7)
      pipe.input.read(out)
      out ==> data
    }

    test("multiple") { // Single sequential write and read works
      val pipe = new PipeStreams()
      val chunkSize = 10
      val chunkCount = 100
      for (i <- Range(0, chunkCount)) {
        pipe.output.write(Array.fill(chunkSize)(i.toByte))
      }

      for (i <- Range(0, chunkCount)) {
        pipe.input.readNBytes(chunkSize) ==> Array.fill(chunkSize)(i.toByte)
      }
    }
    test("concurrentWriteRead") { // Single sequential write and read works
      val pipe = new PipeStreams(bufferSize = 13)
      val chunkSize = 20
      val chunkCount = 100
      assert(pipe.bufferSize < chunkSize * chunkCount) // ensure it gets filled
      Future {
        for (i <- Range(0, chunkCount)) {
          pipe.output.write(Array.fill(chunkSize)(i.toByte))
        }
      }

      Thread.sleep(100) // Give it time for pipe to fill up

      val reader = Future {
        for (_ <- Range(0, chunkCount)) yield {
          pipe.input.readNBytes(chunkSize).toSeq
        }
      }

      val result = Await.result(reader, Inf)
      val expected = Seq.tabulate(chunkCount)(i => Array.fill(chunkSize)(i.toByte).toSeq)
      result ==> expected
    }
    test("multiThreadWrite") { // multiple writes across different threads followed by read
      val chunkSize = 10
      val chunkCount = 100
      val pipe = new PipeStreams()
      assert(chunkSize * chunkCount < pipe.bufferSize)
      val writerPool = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(40))
      val writeFutures =
        for (i <- Range(0, chunkCount)) yield Future {
          pipe.output.write(Array.fill(chunkSize)(i.toByte))
        }(using writerPool)

      Await.ready(Future.sequence(writeFutures), Inf)

      val out = pipe.input.readNBytes(chunkSize * chunkCount)

      val expectedLists =
        for (i <- Range(0, chunkCount))
          yield Array.fill(chunkSize)(i.toByte).toSeq

      val sortedGroups = out.toSeq.grouped(chunkSize).toSeq.sortBy(_.head).toVector

      sortedGroups ==> expectedLists
    }
    test(
      "multiThreadWriteConcurrentRead"
    ) { // multiple writes across different threads interleaved by reads
      val chunkSize = 20
      val chunkCount = 100
      val pipe = new PipeStreams(bufferSize = 113)
      assert(chunkSize * chunkCount > pipe.bufferSize)
      val writerPool = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(40))
      for (i <- Range(0, chunkCount)) yield Future {
        pipe.output.write(Array.fill(chunkSize)(i.toByte))
      }(using writerPool)

      val out = pipe.input.readNBytes(chunkSize * chunkCount)

      val expectedLists =
        for (i <- Range(0, chunkCount))
          yield Array.fill(chunkSize)(i.toByte).toSeq

      val sortedGroups = out.toSeq.grouped(chunkSize).toSeq.sortBy(_.head).toVector

      sortedGroups ==> expectedLists
    }
    // Not sure why this use case doesn't work yet
    //    test("multiThreadWriteMultiThreadRead"){ // multiple writes across different threads interleaved by reads
    //      val chunkSize = 20
    //      val chunkCount = 100
    //      val pipe = new PipeStreams(bufferSize = 137)
    //      val writerPool = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(100))
    //      val readerPool = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(100))
    //      for(i <- Range(0, chunkCount)) yield Future{
    //        pipe.output.write(Array.fill(chunkSize)(i.toByte))
    //      }(writerPool)
    //
    //      val reader = for (i <- Range(0, chunkCount)) yield  Future {
    //        pipe.input.readNBytes(chunkSize).toSeq
    //      }(readerPool)
    //
    //
    //      val out = Await.result(Future.sequence(reader), Inf)
    //      val expectedLists =
    //        for(i <- Range(0, chunkCount))
    //          yield Array.fill(chunkSize)(i.toByte).toSeq
    //
    //      val sortedGroups = out.sortBy(_.head).toVector
    //
    //      pprint.log(sortedGroups)
    //      pprint.log(expectedLists)
    //      sortedGroups ==> expectedLists
    //    }
  }
}
