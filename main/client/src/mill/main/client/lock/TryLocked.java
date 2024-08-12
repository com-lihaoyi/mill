package mill.main.client.lock;

public interface TryLocked extends Locked {
    public boolean isLocked();
}
