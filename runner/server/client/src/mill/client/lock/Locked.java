package mill.client.lock;

public interface Locked extends AutoCloseable {

  void release() throws Exception;

  default void close() throws Exception {
    release();
  }
}
