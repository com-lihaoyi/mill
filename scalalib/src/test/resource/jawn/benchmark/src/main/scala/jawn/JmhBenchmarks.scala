package jawn
package benchmark

import java.io.{BufferedReader, File, FileInputStream, FileReader}
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import scala.collection.mutable

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
abstract class JmhBenchmarks(name: String) {
  val path: String = s"src/main/resources/$name"

  def load(path: String): String = {
    val file = new File(path)
    val bytes = new Array[Byte](file.length.toInt)
    val fis = new FileInputStream(file)
    fis.read(bytes)
    new String(bytes, "UTF-8")
  }

  def reader(path: String): FileReader =
    new FileReader(new File(path))

  def buffered(path: String): BufferedReader =
    new BufferedReader(new FileReader(new File(path)))

  @Benchmark
  def jawnCheckSyntax() =
    jawn.Syntax.checkString(load(path))

  @Benchmark
  def jawnParse() =
    jawn.ast.JParser.parseFromFile(new File(path)).get

  @Benchmark
  def jawnStringParse() =
    jawn.ast.JParser.parseFromString(load(path)).get
}

trait OtherBenchmarks { self: JmhBenchmarks =>
  @Benchmark
  def json4sJacksonParse() = {
    import org.json4s._
    import org.json4s.jackson.JsonMethods._
    parse(load(path))
  }

  @Benchmark
  def playParse() =
    play.api.libs.json.Json.parse(load(path))

  @Benchmark
  def rojomaV3Parse() =
    com.rojoma.json.v3.io.JsonReader.fromReader(reader(path), blockSize = 100000)

  @Benchmark
  def argonautParse() =
    argonaut.Parse.parse(load(path))

  @Benchmark
  def sprayParse() =
    spray.json.JsonParser(load(path))

  @Benchmark
  def parboiledJsonParse() =
    new ParboiledParser(load(path)).Json.run().get

  @Benchmark
  def jacksonParse() = {
    import com.fasterxml.jackson.databind.ObjectMapper
    import com.fasterxml.jackson.databind.JsonNode
    new ObjectMapper().readValue(new File(path), classOf[JsonNode])
  }

  @Benchmark
  def gsonParse() =
    new com.google.gson.JsonParser().parse(buffered(path))

  // don't bother benchmarking jawn + external asts by default

  // @Benchmark
  // def json4sJawnParse() =
  //   jawn.support.json4s.Parser.parseFromFile(new File(path)).get
  //
  // @Benchmark
  // def rojomaV3JawnParse() =
  //   jawn.support.rojoma.v3.Parser.parseFromFile(new File(path)).get
  //
  // @Benchmark
  // def argonautJawnParse() =
  //   jawn.support.argonaut.Parser.parseFromFile(new File(path)).get
  //
  // @Benchmark
  // def sprayJawnParse() =
  //   jawn.support.spray.Parser.parseFromFile(new File(path)).get

  // native json4s parser is really, really slow, so it's disabled by default.

  // @Benchmark
  // def json4sNativeParse() = {
  //   import org.json4s._
  //   import org.json4s.native.JsonMethods._
  //   parse(load(path))
  // }
}

class Qux2Bench extends JmhBenchmarks("qux2.json") with OtherBenchmarks
class Bla25Bench extends JmhBenchmarks("bla25.json") with OtherBenchmarks
class CountriesBench extends JmhBenchmarks("countries.geo.json") with OtherBenchmarks
class Ugh10kBench extends JmhBenchmarks("ugh10k.json") with OtherBenchmarks

class JawnOnlyQux2Bench extends JmhBenchmarks("qux2.json")
class JawnOnlyBla25Bench extends JmhBenchmarks("bla25.json")
class JawnOnlyCountriesBench extends JmhBenchmarks("countries.geo.json")
class JawnOnlyUgh10kBench extends JmhBenchmarks("ugh10k.json")

// // from https://github.com/zemirco/sf-city-lots-json
// class CityLotsBench extends JmhBenchmarks("citylots.json")
