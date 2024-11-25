package mill.main.client

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.Objects

class RetryRule(retryCount: Int) extends TestRule {

  override def apply(base: Statement, description: Description): Statement = {
    new Statement {
      override def evaluate(): Unit = {
        var caughtThrowable: Throwable = null
        // implement retry logic here
        for (i <- 0 until retryCount) {
          try {
            base.evaluate()
            return
          } catch {
            case t: Throwable =>
              caughtThrowable = t
              System.err.println(s"${description.getDisplayName}: run ${i + 1} failed.")
          }
        }
        System.err.println(s"${description.getDisplayName}: Giving up after $retryCount failures.")
        throw Objects.requireNonNull(caughtThrowable)
      }
    }
  }
}
