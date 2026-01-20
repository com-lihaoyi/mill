package mill.api.daemon

/**
 * Version state that is persisted to disk to survive daemon restarts.
 * Used to detect when Mill version or JVM version changes, which invalidates all tasks.
 *
 * Note: upickle.ReadWriter is provided separately in the module that performs serialization
 * since mill.api.daemon doesn't have upickle as a dependency.
 */
case class VersionState(millVersion: String, millJvmVersion: String)
