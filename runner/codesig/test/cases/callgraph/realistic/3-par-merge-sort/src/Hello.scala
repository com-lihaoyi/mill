package hello

// Taken from https://github.com/handsonscala/handsonscala/blob/ebc0367144513fc181281a024f8071a6153be424/examples/13.7%20-%20ParallelMergeSort/MergeSort.sc
import mainargs.main

object Main {

  import scala.concurrent._, duration.Duration.Inf, java.util.concurrent.Executors

  implicit val ec: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))

  def mergeSortParallel[T: Ordering](items: IndexedSeq[T]): IndexedSeq[T] = {
    Await.result(mergeSortParallel0(items), Inf)
  }

  def mergeSortParallel0[T: Ordering](items: IndexedSeq[T]): Future[IndexedSeq[T]] = {
    if (items.length <= 16) Future.successful(mergeSortSequential(items))
    else {
      val (left, right) = items.splitAt(items.length / 2)
      mergeSortParallel0(left).zip(mergeSortParallel0(right)).map {
        case (sortedLeft, sortedRight) => merge(sortedLeft, sortedRight)
      }
    }
  }

  def mergeSortSequential[T: Ordering](items: IndexedSeq[T]): IndexedSeq[T] = {
    if (items.length <= 1) items
    else {
      val (left, right) = items.splitAt(items.length / 2)
      merge(mergeSortSequential(left), mergeSortSequential(right))
    }
  }

  def merge[T: Ordering](sortedLeft: IndexedSeq[T], sortedRight: IndexedSeq[T]) = {
    var leftIdx = 0
    var rightIdx = 0
    val output = IndexedSeq.newBuilder[T]
    while (leftIdx < sortedLeft.length || rightIdx < sortedRight.length) {
      val takeLeft = (leftIdx < sortedLeft.length, rightIdx < sortedRight.length) match {
        case (true, false) => true
        case (false, true) => false
        case (true, true) => Ordering[T].lt(sortedLeft(leftIdx), sortedRight(rightIdx))
        case (false, false) => throw Exception("impossible")
      }
      if (takeLeft) {
        output += sortedLeft(leftIdx)
        leftIdx += 1
      } else {
        output += sortedRight(rightIdx)
        rightIdx += 1
      }
    }
    output.result()
  }
}

/* expected-direct-call-graph
{
    "hello.Main$#mergeSortParallel(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq": [
        "hello.Main$#mergeSortParallel0(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.concurrent.Future"
    ],
    "hello.Main$#mergeSortParallel0(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.concurrent.Future": [
        "hello.Main$#ec()scala.concurrent.ExecutionContextExecutorService",
        "hello.Main$#merge(scala.collection.immutable.IndexedSeq,scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq",
        "hello.Main$#mergeSortParallel0(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.concurrent.Future",
        "hello.Main$#mergeSortSequential(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq"
    ],
    "hello.Main$#mergeSortSequential(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq": [
        "hello.Main$#merge(scala.collection.immutable.IndexedSeq,scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq",
        "hello.Main$#mergeSortSequential(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq"
    ],
    "hello.Main.ec()scala.concurrent.ExecutionContextExecutorService": [
        "hello.Main$#<init>()void",
        "hello.Main$#ec()scala.concurrent.ExecutionContextExecutorService"
    ],
    "hello.Main.merge(scala.collection.immutable.IndexedSeq,scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq": [
        "hello.Main$#<init>()void",
        "hello.Main$#merge(scala.collection.immutable.IndexedSeq,scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq"
    ],
    "hello.Main.mergeSortParallel(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq": [
        "hello.Main$#<init>()void",
        "hello.Main$#mergeSortParallel(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq"
    ],
    "hello.Main.mergeSortParallel0(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.concurrent.Future": [
        "hello.Main$#<init>()void",
        "hello.Main$#mergeSortParallel0(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.concurrent.Future"
    ],
    "hello.Main.mergeSortSequential(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq": [
        "hello.Main$#<init>()void",
        "hello.Main$#mergeSortSequential(scala.collection.immutable.IndexedSeq,scala.math.Ordering)scala.collection.immutable.IndexedSeq"
    ]
}
 */
