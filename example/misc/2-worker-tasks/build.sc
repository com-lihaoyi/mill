import mill._, scalalib._
import java.util.Arrays, java.io.ByteArrayOutputStream, java.util.zip.GZIPOutputStream

def data = T.source(millSourcePath / "data")

def compressWorker = T.worker{ new CompressWorker(T.dest) }

def compressedData = T{
  println("Evaluating compressedData")
  for(p <- os.list(data().path)){
    os.write(
      T.dest / s"${p.last}.gz",
      compressWorker().compress(p.last, os.read.bytes(p))
    )
  }
  os.list(T.dest).map(PathRef(_))
}

class CompressWorker(dest: os.Path){
  val cache = collection.mutable.Map.empty[Int, Array[Byte]]
  def compress(name: String, bytes: Array[Byte]): Array[Byte] = {
    val hash = Arrays.hashCode(bytes)
    if (!cache.contains(hash)) {
      val cachedPath = dest / hash.toHexString
      println("cachedPath: " + cachedPath)
      if (!os.exists(cachedPath)) {
        println("Compressing: " + name)
        cache(hash) = compressBytes(bytes)
        os.write(cachedPath, cache(hash))
      }else{
        println("Cached from disk: " + name)
        cache(hash) = os.read.bytes(cachedPath)
      }
    }else {
      println("Cached from memory: " + name)
    }
    cache(hash)
  }
}

def compressBytes(input: Array[Byte]) = {
  val bos = new ByteArrayOutputStream(input.length)
  val gzip = new GZIPOutputStream(bos)
  gzip.write(input)
  gzip.close()
  bos.toByteArray
}

// Mill workers defined using `T.worker` are long-lived in-memory objects that
// can persistent across multiple evaluations. These are similar to persistent
// targets in that they let you cache things, but the fact that they let you
// cache the worker object in-memory allows for greater performance and
// flexibility: you are no longer limited to caching only serialization data
// and paying the cost of serializing it to disk every evaluation. This example
// uses a Worker to provide simple in-memory caching for compressed files.
//
// Common things to put in workers include:
//
// 1. References to third-party daemon processes, e.g. Webpack or wkhtmltopdf,
//    which perform their own in-memory caching
//
// 2. Classloaders containing plugin code, to avoid classpath conflicts while
//    also avoiding classloading cost every time the code is executed
//
// Workers live as long as the Mill process. By default, consecutive `mill`
// commands in the same folder will re-use the same Mill process and workers,
// unless `--no-server` is passed which will terminate the Mill process and
// workers after every command. Commands run repeatedly using `--watch` will
// also preserve the workers between them.
//
// Workers can also make use of their `T.dest` folder as a cache that persist
// when the worker shuts down, as a second layer of caching. The example usage
// below demonstrates how using the `--no-server` flag will make the worker
// read from its disk cache, where it would have normally read from its
// in-memory cache

/* Example Usage

> ./mill show compressedData
Evaluating compressedData
Compressing: hello.txt
Compressing: world.txt
hello.txt.gz
world.txt.gz

> ./mill compressedData # when no input changes, compressedData does not evaluate at all

> sed -i 's/Hello/HELLO/g' data/hello.txt

> ./mill compressedData # not --no-server, we read the data from memory
Compressing: hello.txt
Cached from memory: world.txt

> ./mill compressedData # --no-server, we read the data from disk
Compressing: hello.txt
Cached from disk: world.txt

*/