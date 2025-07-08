package mill.javalib.jarjarabrams.impl

import com.eed3si9n.jarjarabrams.{ShadePattern, Shader}

import java.io.{ByteArrayInputStream, InputStream}

object JarJarAbramsWorkerImpl {
  type UnopenedInputStream = () => InputStream

  def apply(
      relocates: Seq[(String, String)],
      name: String,
      inputStream: UnopenedInputStream
  ): Option[(String, UnopenedInputStream)] = {
    val shadeRules = relocates.map {
      case (from, to) => ShadePattern.Rename(List(from -> to)).inAll
    }
    if (shadeRules.isEmpty) Some(name -> inputStream)
    else {
      val shader = Shader.bytecodeShader(shadeRules, verbose = false, skipManifest = true)
      val is = inputStream()
      shader(is.readAllBytes(), name).map {
        case (bytes, name) =>
          name ->
            (() => new ByteArrayInputStream(bytes) { override def close(): Unit = is.close() })
      }
    }
  }
}
