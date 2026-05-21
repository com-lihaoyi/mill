package repro

import org.junit.jupiter.api.Test

class DeletedFailingTest {
  @Test
  fun deleted_test_must_not_run() {
    throw AssertionError("stale DeletedFailingTest still running")
  }
}
