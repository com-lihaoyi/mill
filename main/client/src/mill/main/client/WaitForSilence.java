package mill.main.client;

/**
  * Waits for a minimum period of silence after being poked from another thread
  */

class WaitForSilence {
  private long last = System.currentTimeMillis();

  public synchronized long getLast() {
      return last;
  }

  public synchronized void poke() {
      this.last = System.currentTimeMillis();
  }

  public void waitForSilence(int millis) throws InterruptedException {
    do {
        Thread.sleep(10);
    } while ((System.currentTimeMillis() - getLast()) < millis);
  }
}
