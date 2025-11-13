package mill.runner.autooverride

/**
 * A trait that enables automatic implementation of abstract methods.
 *
 * Any concrete object that extends AutoOverride[T] will have any unimplemented
 * abstract methods with return type <: T automatically implemented by calling
 * this.autoOverrideImpl().
 *
 * @tparam T the return type that will be automatically implemented
 */
trait AutoOverride[T] {
  /**
   * The implementation to use for all auto-overridden methods.
   * This method will be called for every abstract method with return type <: T
   * that is not explicitly implemented.
   */
  def autoOverrideImpl(): T
}
