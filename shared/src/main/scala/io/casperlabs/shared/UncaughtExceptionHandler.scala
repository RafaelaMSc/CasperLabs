package io.casperlabs.shared

import monix.eval.Task
import monix.execution.UncaughtExceptionReporter

import scala.concurrent.duration.FiniteDuration

class UncaughtExceptionHandler(shutdownTimeout: FiniteDuration)(implicit log: Log[Task])
    extends UncaughtExceptionReporter
    with RuntimeOps {
  override def reportFailure(ex: scala.Throwable): Unit = {
    import monix.execution.Scheduler.Implicits.global
    log.error(s"Uncaught Exception : $ex").runSyncUnsafe()
    ex match {
      case _: VirtualMachineError | _: LinkageError | _: FatalErrorShutdown =>
        // To flush logs
        Thread.sleep(3000)
        exitOrHalt(1, shutdownTimeout)
      case _ =>
    }
  }
}
