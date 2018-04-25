package bloop.exec

import java.io.File.{pathSeparator, separator}
import java.lang.ClassLoader
import java.nio.file.Files
import java.net.URLClassLoader
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

import scala.util.control.NonFatal
import bloop.io.AbsolutePath
import bloop.logging.{Logger, ProcessLogger}

/**
 * Configuration to start a new JVM to execute Java code.
 *
 * @param javaEnv   The configuration describing how to start the new JVM.
 * @param classpath The full classpath with which the code should be executed.
 */
final case class JavaProcess(javaEnv: JavaEnv, classpath: Array[AbsolutePath]) {

  /**
   * Creates a `ClassLoader` from the classpath of this `JavaProcess`.
   *
   * @param parent A parent classloader
   * @return A classloader constructed from the classpath of this `JavaProcess`.
   */
  def toExecutionClassLoader(parent: Option[ClassLoader]): ClassLoader = {
    def makeNew(parent: Option[ClassLoader]): ClassLoader = {
      val classpathEntries = classpath.map(_.underlying.toUri.toURL)
      new URLClassLoader(classpathEntries, parent.orNull)
    }
    JavaProcess.classLoaderCache.computeIfAbsent(parent, makeNew)
  }

  /**
   * Run the main function in class `className`, passing it `args`.
   *
   * @param cwd            The directory in which to start the forked JVM.
   * @param className      The fully qualified name of the class to run.
   * @param args           The arguments to pass to the main method.
   * @param logger         Where to log the messages from execution.
   * @param properties     The environment properties to run the program with.
   * @param extraClasspath Paths to append to the classpath before running.
   * @return 0 if the execution exited successfully, a non-zero number otherwise.
   */
  def runMain(cwd: AbsolutePath,
              className: String,
              args: Array[String],
              logger: Logger,
              env: Properties,
              extraClasspath: Array[AbsolutePath] = Array.empty): Int = {
    import scala.collection.JavaConverters.propertiesAsScalaMap
    val fullClasspath = classpath ++ extraClasspath

    val java = javaEnv.javaHome.resolve("bin").resolve("java")
    val classpathOption = "-cp" :: fullClasspath.map(_.syntax).mkString(pathSeparator) :: Nil
    val appOptions = className :: args.toList
    val cmd = java.syntax :: javaEnv.javaOptions.toList ::: classpathOption ::: appOptions

    logger.debug(s"Running '$className' in a new JVM.")
    logger.debug(s"  java_home   = '${javaEnv.javaHome}'")
    logger.debug(s"  javaOptions = '${javaEnv.javaOptions.mkString(" ")}'")
    logger.debug(s"  classpath   = '${fullClasspath.map(_.syntax).mkString(pathSeparator)}'")
    logger.debug(s"  command     = '${cmd.mkString(" ")}'")
    logger.debug(s"  cwd         = '$cwd'")

    Process.run(cwd, cmd, propertiesAsScalaMap(env).toMap, logger)
  }
}

object JavaProcess {

  private val classLoaderCache: ConcurrentHashMap[Option[ClassLoader], ClassLoader] =
    new ConcurrentHashMap
}
