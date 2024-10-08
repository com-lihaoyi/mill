package mill.main.client.lock;

class DummyTryLocked implements TryLocked {
    public DummyTryLocked() {
    }

    public boolean isLocked(){ return true; }

    public void release() throws Exception {
    }
}
