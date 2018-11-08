package mill.eval

import utest._

import mill.T

import mill.util.TestEvaluator
object TaskTests extends TestSuite{
  val tests = Tests{
    object build extends mill.util.TestUtil.BaseModule{
      var count = 0
      // Explicitly instantiate `Function1` objects to make sure we get
      // different instances each time
      def staticWorker = T.worker{
        new Function1[Int, Int] {
          def apply(v1: Int) = v1 + 1
        }
      }
      def noisyWorker = T.worker{
        new Function1[Int, Int] {
          def apply(v1: Int) = input() + 1
        }
      }
      def input = T.input{
        count += 1
        count
      }
      def task = T.task{
        count += 1
        count
      }
      def taskInput = T{ input() }
      def taskNoInput = T{ task() }

      def persistent = T.persistent{
        input() // force re-computation
        os.makeDir.all(T.ctx().dest)
        os.write.append(T.ctx().dest/'count, "hello\n")
        os.read.lines(T.ctx().dest/'count).length
      }
      def nonPersistent = T{
        input() // force re-computation
        os.makeDir.all(T.ctx().dest)
        os.write.append(T.ctx().dest/'count, "hello\n")
        os.read.lines(T.ctx().dest/'count).length
      }

      def staticWorkerDownstream = T{
        staticWorker().apply(1)
      }
      def noisyWorkerDownstream = T{
        noisyWorker().apply(1)
      }
    }

    'inputs - {
      // Inputs always re-evaluate, including forcing downstream cached Targets
      // to re-evaluate, but normal Tasks behind a Target run once then are cached
      val check = new TestEvaluator(build)

      val Right((1, 1)) = check.apply(build.taskInput)
      val Right((2, 1)) = check.apply(build.taskInput)
      val Right((3, 1)) = check.apply(build.taskInput)

      val Right((4, 1)) = check.apply(build.taskNoInput)
      val Right((4, 0)) = check.apply(build.taskNoInput)
      val Right((4, 0)) = check.apply(build.taskNoInput)
    }

    'persistent - {
      // Persistent tasks keep the working dir around between runs
      val check = new TestEvaluator(build)
      val Right((1, 1)) = check.apply(build.persistent)
      val Right((2, 1)) = check.apply(build.persistent)
      val Right((3, 1)) = check.apply(build.persistent)

      val Right((1, 1)) = check.apply(build.nonPersistent)
      val Right((1, 1)) = check.apply(build.nonPersistent)
      val Right((1, 1)) = check.apply(build.nonPersistent)
    }

    'worker - {
      // Persistent task
      def check = new TestEvaluator(build)

      val Right((2, 1)) = check.apply(build.noisyWorkerDownstream)
      val Right((3, 1)) = check.apply(build.noisyWorkerDownstream)
      val Right((4, 1)) = check.apply(build.noisyWorkerDownstream)

      val Right((2, 1)) = check.apply(build.staticWorkerDownstream)
      val Right((2, 0)) = check.apply(build.staticWorkerDownstream)
      val Right((2, 0)) = check.apply(build.staticWorkerDownstream)
    }
  }
}
