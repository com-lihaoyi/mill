package forge
import java.nio.{file => jnio}

trait ReadWrite[T] {
  def write(t: T, p: jnio.Path): Unit
  def read(p: jnio.Path): T
}

object ReadWrite{
  implicit object String extends ReadWrite[java.lang.String]{
    def write(t: String, p: jnio.Path) = {
      jnio.Files.createDirectories(p.getParent)
      jnio.Files.deleteIfExists(p)
      jnio.Files.write(p, t.getBytes)
    }
    def read(p: jnio.Path) = {
      new String(jnio.Files.readAllBytes(p))
    }
  }
}
