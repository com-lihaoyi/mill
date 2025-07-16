package mill.zinc.worker

def intValue(oi: java.util.Optional[Integer], default: Int): Int = {
  if oi.isPresent then oi.get().intValue()
  else default
}