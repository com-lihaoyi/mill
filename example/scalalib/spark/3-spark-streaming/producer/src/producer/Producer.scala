import org.apache.kafka.clients.producer._

object Producer {
  def main(args: Array[String]): Unit = {
    val props = new java.util.Properties()
    props.put("bootstrap.servers", "localhost:9092")
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    val producer = new KafkaProducer[String, String](props)
    producer.send(new ProducerRecord[String, String]("test-topic", "Hello, World!"))
    producer.close()
  }
}
