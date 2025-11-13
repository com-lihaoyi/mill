package mill.runner.autooverride

import utest.*

/**
 * Test suite for the AutoOverride compiler plugin
 */
object AutoOverrideTests extends TestSuite {

  // Test trait with abstract methods returning String
  trait TestService {
    def getName(): String
    def getDescription(): String
    def getVersion(): String
  }

  // Test object that should have methods auto-implemented
  object TestServiceImpl extends TestService with AutoOverride[String] {
    def autoOverrideImpl[T](): T = "auto-generated".asInstanceOf[T]
    // getName, getDescription, and getVersion should be automatically implemented
  }

  // Test trait with mixed return types
  trait MixedService {
    def getValue(): String
    def getCount(): Int
    def isActive(): Boolean
  }

  // Only methods returning String should be auto-implemented
  object MixedServiceImpl extends MixedService with AutoOverride[String] {
    def autoOverrideImpl[T](): T = "default-value".asInstanceOf[T]
    // getValue should be auto-implemented
    // getCount and isActive must be manually implemented
    override def getCount(): Int = 42
    override def isActive(): Boolean = true
  }

  // Test with Any type (should implement all abstract methods)
  trait GenericService {
    def getString(): String
    def getInt(): Int
    def getBoolean(): Boolean
  }

  object GenericServiceImpl extends GenericService with AutoOverride[Any] {
    def autoOverrideImpl[T](): T = null.asInstanceOf[T]
    // All methods should be auto-implemented since Any is a supertype of all
  }

  // Test with partial manual implementation
  trait PartialService {
    def auto1(): String
    def auto2(): String
    def manual(): String
  }

  object PartialServiceImpl extends PartialService with AutoOverride[String] {
    def autoOverrideImpl[T](): T = "auto".asInstanceOf[T]
    override def manual(): String = "manual"
    // auto1 and auto2 should be auto-implemented
  }

  val tests = Tests {
    test("basic auto-implementation") {
      assert(TestServiceImpl.getName() == "auto-generated")
      assert(TestServiceImpl.getDescription() == "auto-generated")
      assert(TestServiceImpl.getVersion() == "auto-generated")
    }

    test("mixed return types") {
      // Auto-implemented String method
      assert(MixedServiceImpl.getValue() == "default-value")

      // Manually implemented methods
      assert(MixedServiceImpl.getCount() == 42)
      assert(MixedServiceImpl.isActive() == true)
    }

    test("generic Any type") {
      // All methods should be implemented with null from autoOverrideImpl
      assert(GenericServiceImpl.getString() == null)
      // For primitives, null gets converted to default values (0, false)
      // This is expected JVM behavior when unboxing null
      assert(GenericServiceImpl.getInt() == 0)
      assert(GenericServiceImpl.getBoolean() == false)
    }

    test("partial manual implementation") {
      // Auto-implemented methods
      assert(PartialServiceImpl.auto1() == "auto")
      assert(PartialServiceImpl.auto2() == "auto")

      // Manually implemented method
      assert(PartialServiceImpl.manual() == "manual")
    }

    test("methods are callable multiple times") {
      val first = TestServiceImpl.getName()
      val second = TestServiceImpl.getName()
      assert(first == second)
      assert(first == "auto-generated")
    }

    test("indirect inheritance") {
      // Test that AutoOverride works when inherited through a base class
      trait IndirectService {
        def getData(): String
      }

      abstract class BaseService extends IndirectService with AutoOverride[String] {
        def autoOverrideImpl[T](): String = "indirect-value"
      }

      object IndirectServiceImpl extends BaseService {
        // getData should be auto-implemented via BaseService
      }

      assert(IndirectServiceImpl.getData() == "indirect-value")
    }
  }
}
