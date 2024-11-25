package mill.main.client.lock

trait TryLocked extends Locked {
  def isLocked: Boolean
}
