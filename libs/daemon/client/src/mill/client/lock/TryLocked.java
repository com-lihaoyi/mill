package mill.client.lock;

public interface TryLocked extends Locked {
  boolean isLocked();
}
