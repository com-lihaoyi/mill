package mill.client.lock;

public interface Locked extends AutoCloseable {

  void release() throws Exception;

  @Override
  default void close() throws Exception {
    release();
  }
}
