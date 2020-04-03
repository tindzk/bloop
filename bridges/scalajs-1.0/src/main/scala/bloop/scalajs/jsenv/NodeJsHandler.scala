package bloop.scalajs.jsenv

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Path

import bloop.exec.Forker
import bloop.logging.{DebugFilter, Logger}
import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess}
import monix.execution.atomic.AtomicBoolean
import org.scalajs.jsenv.JSUtils

import scala.concurrent.Promise

final class NodeJsHandler(logger: Logger, exit: Promise[Unit], files: List[Path])
    extends NuAbstractProcessHandler {
  implicit val debugFilter: DebugFilter = DebugFilter.Link
  private var currentFileIndex: Int = 0
  private var currentStream: Option[InputStream] = None

  private var process: Option[NuProcess] = None

  override def onStart(nuProcess: NuProcess): Unit = {
    logger.debug(s"Process started at PID ${nuProcess.getPID}")
    process = Some(nuProcess)
  }

  /** @return false if we have nothing else to write */
  override def onStdinReady(output: ByteBuffer): Boolean = {
    if (currentFileIndex < files.length) {
      val path = files(currentFileIndex).toAbsolutePath.toString
      logger.debug(s"Sending JavaScript file $path...")
      val str = s"""require("${JSUtils.escapeJS(path)}");"""
      output.put(str.getBytes("UTF-8"))
      currentFileIndex += 1
      output.flip()
    }

    if (currentFileIndex == files.length) {
      logger.debug(s"Closing stdin stream (all js files have been sent)")
      process.get.closeStdin(false)
    }

    currentFileIndex < files.length
  }

  val outBuilder = StringBuilder.newBuilder
  override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
    if (closed) {
      val remaining = outBuilder.mkString
      if (!remaining.isEmpty)
        logger.info(remaining)
    } else {
      Forker.onEachLine(buffer, outBuilder)(logger.info(_))
    }
  }

  // Scala-js redirects the error stream to out as well, so we duplicate its behavior
  override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
    if (closed) {
      val remaining = outBuilder.mkString
      if (!remaining.isEmpty)
        logger.info(remaining)
    } else {
      Forker.onEachLine(buffer, outBuilder)(logger.info(_))
    }
  }

  private val hasExited = AtomicBoolean(false)
  def cancel(): Unit = onExit(0)
  override def onExit(statusCode: Int): Unit = {
    if (!hasExited.getAndSet(true)) {
      logger.debug(s"Process exited with status code $statusCode")
      currentStream.foreach(_.close())
      currentStream = None
      exit.success(())
    }
  }
}
