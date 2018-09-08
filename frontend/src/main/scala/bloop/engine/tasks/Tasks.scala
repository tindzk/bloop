package bloop.engine.tasks

import java.nio.file.{Files, Path}

import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.config.Config.Platform
import bloop.engine.caches.ResultsCache
import bloop.engine.{Dag, Feedback, Leaf, Parent, State}
import bloop.exec.Forker
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.BspLogger
import bloop.reporter.{BspReporter, LogReporter, Problem, ReporterConfig}
import bloop.testing.{
  DiscoveredTestFrameworks,
  LoggingEventHandler,
  TestInternals,
  TestSuiteEvent,
  TestSuiteEventHandler
}
import bloop.{CompileInputs, Compiler, Project, ScalaInstance}
import monix.eval.Task
import sbt.internal.inc.bloop.CompileMode
import sbt.internal.inc.{Analysis, AnalyzingCompiler, ConcreteAnalysisContents, FileAnalysisStore}
import sbt.internal.inc.classpath.ClasspathUtilities
import sbt.internal.inc.javac.JavaNoPosition
import sbt.testing._
import xsbt.api.Discovery
import xsbti.Severity
import xsbti.compile.{ClasspathOptionsUtil, CompileAnalysis, MiniSetup, PreviousResult}

import scala.util.{Failure, Success}

object Tasks {
  private val TestFailedStatus: Set[Status] =
    Set(Status.Failure, Status.Error, Status.Canceled)

  private val dateFormat = new java.text.SimpleDateFormat("HH:mm:ss.SSS")
  private def currentTime: String = dateFormat.format(new java.util.Date())

  private case class CompileResult(project: Project, result: Compiler.Result) {
    // Cache hash code here so that `Dag.toDotGraph` doesn't recompute it all the time
    override val hashCode: Int = scala.util.hashing.MurmurHash3.productHash(this)
  }

  private type CompileTask = Task[Dag[CompileResult]]

  import scalaz.Show
  private final implicit val showCompileTask: Show[CompileResult] = new Show[CompileResult] {
    private def seconds(ms: Double): String = s"${ms}ms"
    override def shows(r: CompileResult): String = {
      val project = r.project
      r.result match {
        case Compiler.Result.Empty => s"${project.name} (empty)"
        case Compiler.Result.Cancelled(ms) => s"${project.name} (cancelled, lasted ${ms}ms)"
        case Compiler.Result.Success(_, _, ms) => s"${project.name} (success ${ms}ms)"
        case Compiler.Result.Blocked(on) => s"${project.name} (blocked on ${on.mkString(", ")})"
        case Compiler.Result.Failed(problems, t, ms) =>
          val extra = t match {
            case Some(t) => s"exception '${t.getMessage}', "
            case None => ""
          }
          s"${project.name} (failed with ${Problem.count(problems)}, $extra${ms}ms)"
      }
    }
  }

  /**
   * Performs incremental compilation of the dependencies of `project`, including `project` if
   * `excludeRoot` is `false`, excluding it otherwise.
   *
   * @param state          The current state of Bloop.
   * @param project        The project to compile.
   * @param reporterConfig Configuration of the compilation messages reporter.
   * @param deduplicateFailedCompilation Don't compile a target if it has failed in the same compile run.
   * @param excludeRoot    If `true`, compile only the dependencies of `project`. Otherwise, includ `project`.
   * @return The new state of Bloop after compilation.
   */
  def compile(
      state: State,
      project: Project,
      reporterConfig: ReporterConfig,
      deduplicateFailedCompilation: Boolean,
      compileMode: CompileMode.ConfigurableMode,
      excludeRoot: Boolean
  ): Task[State] = {
    import state.{logger, compilerCache}
    def toInputs(
        project: Project,
        instance: ScalaInstance,
        sources: Array[AbsolutePath],
        config: ReporterConfig,
        result: PreviousResult
    ) = {
      val classpath = project.classpath
      val classesDir = project.classesDir
      val target = project.out
      val scalacOptions = project.scalacOptions.toArray
      val javacOptions = project.javacOptions.toArray
      val classpathOptions = project.classpathOptions
      val cwd = state.build.origin.getParent
      val compileOrder = project.compileOrder

      // Set the reporter based on the kind of logger to publish diagnostics
      val reporter = logger match {
        case bspLogger: BspLogger =>
          // Don't show errors in reverse order, log as they come!
          new BspReporter(project, bspLogger, cwd, identity, config.copy(reverseOrder = false))
        case _ => new LogReporter(logger, cwd, identity, config)
      }

      // FORMAT: OFF
      CompileInputs(instance, compilerCache, sources, classpath, Array(), classesDir, target, scalacOptions, javacOptions, compileOrder, classpathOptions, result, reporter, compileMode, logger)
      // FORMAT: ON
    }

    def compile(project: Project): Task[Compiler.Result] = {
      def err(msg: String): Problem =
        Problem(-1, Severity.Error, msg, JavaNoPosition, "")

      val sources = project.sources.distinct
      val javaSources = sources.flatMap(src => Paths.getAllFiles(src, "glob:**.java")).distinct
      val scalaSources = sources.flatMap(src => Paths.getAllFiles(src, "glob:**.scala")).distinct
      val uniqueSources = (javaSources ++ scalaSources).toArray
      project.scalaInstance match {
        case Some(instance) =>
          logger.debug(s"Scheduled compilation of '$project' starting at $currentTime.")
          val previous = state.results.lastSuccessfulResult(project)
          Compiler.compile(toInputs(project, instance, uniqueSources, reporterConfig, previous))

        case None =>
          val addScalaConfiguration = err(
            "Add Scala configuration to the project. If that doesn't fix it, report it upstream")
          // Either return empty result or report
          (scalaSources, javaSources) match {
            case (Nil, Nil) => Task.now(Compiler.Result.Empty)
            case (_: List[AbsolutePath], Nil) =>
              // Let's notify users there is no Scala configuration for a project with Scala sources
              val msg =
                s"Failed to compile project '${project.name}': found Scala sources but project is missing Scala configuration."
              Task.now(Compiler.Result.Failed(List(err(msg), addScalaConfiguration), None, 1))
            case (_, _: List[AbsolutePath]) =>
              // If Java sources exist, we cannot compile them without an instance, fail fast!
              val msg =
                s"Failed to compile ${project.name}'s Java sources because the default Scala instance couldn't be created."
              Task.now(Compiler.Result.Failed(List(err(msg), addScalaConfiguration), None, 1))
          }
      }
    }

    def failed(results: List[(Project, Compiler.Result)]): List[Project] = {
      results.collect {
        case (p, _: Compiler.Result.Cancelled) => p
        case (p, _: Compiler.Result.Failed) => p
      }
    }

    val dag = state.build.getDagFor(project)
    def triggerCompile: Task[State] = {
      toCompileTask(dag, compile(_)).map { results0 =>
        if (logger.isVerbose)
          logger.debug(Dag.toDotGraph(results0))
        val results = Dag.dfs(results0).map(r => (r.project, r.result))
        val failures = failed(results).distinct
        val newState = state.copy(results = state.results.addResults(results))
        if (failures.isEmpty) newState.copy(status = ExitStatus.Ok)
        else {
          failures.foreach(p => logger.error(s"'${p.name}' failed to compile."))
          newState.copy(status = ExitStatus.CompilationError)
        }
      }
    }

    if (!deduplicateFailedCompilation) triggerCompile
    else {
      // Check dependent projects didn't fail in previous sequential compile
      val allDependencies = Dag.dfs(dag).toSet
      val dependentResults =
        state.results.allResults.filter(pr => allDependencies.contains(pr._1))
      val failedDependentProjects = failed(dependentResults.toList)
      if (!failedDependentProjects.isEmpty) {
        val failedProjects = failedDependentProjects.map(p => s"'${p.name}'").mkString(", ")
        logger.warn(
          s"Skipping compilation of project '$project'; dependent $failedProjects failed to compile.")
        Task.now(state.copy(status = ExitStatus.CompilationError))
      } else triggerCompile
    }
  }

  /**
   * Turns a dag of projects into a task that returns a dag of compilation results
   * that can then be used to debug the evaluation of the compilation within Monix
   * and access the compilation results received from Zinc.
   *
   * @param dag The dag of projects to be compiled.
   * @return A task that returns a dag of compilation results.
   */
  private def toCompileTask(
      dag: Dag[Project],
      compile: Project => Task[Compiler.Result]
  ): CompileTask = {
    val tasks = new scala.collection.mutable.HashMap[Dag[Project], CompileTask]()
    def register(k: Dag[Project], v: CompileTask): CompileTask = { tasks.put(k, v); v }

    def blockedBy(dag: Dag[CompileResult]): Option[Project] = {
      dag match {
        case Leaf(CompileResult(_, Compiler.Result.Ok(_))) => None
        case Leaf(CompileResult(project, _)) => Some(project)
        case Parent(CompileResult(_, Compiler.Result.Ok(_)), _) => None
        case Parent(CompileResult(project, _), _) => Some(project)
      }
    }

    def loop(dag: Dag[Project]): CompileTask = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task = dag match {
            case Leaf(project) => compile(project).map(res => Leaf(CompileResult(project, res)))
            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { results =>
                val failed = results.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) {
                  compile(project).map(res => Parent(CompileResult(project, res), results))
                } else {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Compiler.Result.Blocked(failed.map(_.name))
                  Task.now(Parent(CompileResult(project, blocked), results))
                }
              }
          }
          register(dag, task.memoize)
      }
    }

    loop(dag)
  }

  /**
   * Cleans the previous results of the projects specified in `targets`.
   *
   * @param state   The current state of Bloop.
   * @param targets The projects to clean.
   * @param includeDeps Do not run clean for dependencies.
   * @return The new state of Bloop after cleaning.
   */
  def clean(state: State, targets: List[Project], includeDeps: Boolean): Task[State] = Task {
    val allTargetsToClean =
      if (!includeDeps) targets
      else targets.flatMap(t => Dag.dfs(state.build.getDagFor(t))).distinct
    val newResults = state.results.cleanSuccessful(allTargetsToClean)
    state.copy(results = newResults)
  }

  /**
   * Starts a Scala REPL with the dependencies of `project` on the classpath, including `project`
   * if `noRoot` is false, excluding it otherwise.
   *
   * @param state   The current state of Bloop.
   * @param project The project for which to start the REPL.
   * @param noRoot  If false, include `project` on the classpath. Do not include it otherwise.
   * @return The new state of Bloop.
   */
  def console(
      state: State,
      project: Project,
      noRoot: Boolean
  ): Task[State] = Task {
    import state.logger
    project.scalaInstance match {
      case Some(instance) =>
        val classpath = project.classpath
        val entries = classpath.map(_.underlying.toFile).toSeq
        logger.debug(s"Setting up the console classpath with ${entries.mkString(", ")}")
        val loader = ClasspathUtilities.makeLoader(entries, instance)
        val compiler = state.compilerCache.get(instance).scalac.asInstanceOf[AnalyzingCompiler]
        val opts = ClasspathOptionsUtil.repl
        val options = project.scalacOptions :+ "-Xnojline"
        // We should by all means add better error handling here!
        compiler.console(entries, options, opts, "", "", state.logger)(Some(loader))
      case None => logger.error(s"Missing Scala configuration on project '${project.name}'")
    }

    state
  }

  /**
   * Persists every analysis file (the state of the incremental compiler) on disk in parallel.
   *
   * @param state The current state of Bloop
   * @return The task that will persist all the results in parallel.
   */
  def persist(state: State): Task[Unit] = {
    val out = state.commonOptions.ngout
    import bloop.util.JavaCompat.EnrichOptional
    def persist(project: Project, result: PreviousResult): Unit = {
      def toBinaryFile(analysis: CompileAnalysis, setup: MiniSetup): Unit = {
        val storeFile = project.analysisOut
        state.commonOptions.ngout.println(s"Writing ${storeFile.syntax}.")
        FileAnalysisStore.binary(storeFile.toFile).set(ConcreteAnalysisContents(analysis, setup))
        ResultsCache.persisted.add(result)
        ()
      }

      val analysis = result.analysis().toOption
      val setup = result.setup().toOption
      (analysis, setup) match {
        case (Some(analysis), Some(setup)) =>
          if (ResultsCache.persisted.contains(result)) ()
          else toBinaryFile(analysis, setup)
        case (Some(analysis), None) =>
          out.println(s"$project has analysis but not setup after compilation. Report upstream.")
        case (None, Some(analysis)) =>
          out.println(s"$project has setup but not analysis after compilation. Report upstream.")
        case (None, None) => out.println(s"Project $project has no analysis and setup.")
      }
    }

    val ts = state.results.allSuccessful.map { case (p, result) => Task(persist(p, result)) }
    Task.gatherUnordered(ts).map(_ => ())
  }

  private def linkJs(config: Config.JsConfig,
                     project: Project,
                     state: State,
                     target: AbsolutePath,
                     mainClass: Option[String]): Task[Boolean] = {
    import state.logger
    project.jsToolchain match {
      case Some(toolchain) =>
        // TODO Only link if generated SJSIR files have changed
        toolchain.link(config, project, mainClass, target, state.logger).map {
          case Success(_) =>
            logger.info(s"Generated JavaScript file '${target.syntax}'")
            true
          case Failure(ex) =>
            logger.trace(ex)
            logger.error(s"JavaScript linking failed with '${ex.getMessage}'")
            false
        }

      case None =>
        val artifactName = ScalaJsToolchain.artifactNameFrom(config.version)
        val msg = Feedback.missingLinkArtifactFor(project, artifactName, ScalaJsToolchain.name)
        state.logger.error(msg)
        Task.now(false)
    }
  }

  /**
   * Links project if needed and looks up test frameworks
   */
  private def discoverTestFrameworks(project: Project,
                                     state: State): Task[Option[DiscoveredTestFrameworks]] = {
    import state.logger
    project.platform match {
      case Platform.Js(config, _) =>
        val target = ScalaJsToolchain.linkTargetFrom(config, project.out)
        linkJs(config, project, state, target, None).map { success =>
          if (!success) None
          else {
            logger.debug(s"Resolving test frameworks: ${project.testFrameworks.map(_.names)}")
            val (frameworks, dispose) = project.jsToolchain.get.testFrameworks(
              project.testFrameworks.map(_.names.toArray).toArray,
              target,
              project.baseDirectory,
              logger)
            Some(DiscoveredTestFrameworks.Js(frameworks.toList, dispose))
          }
        }

      case _ =>
        val forker = Forker(project.javaEnv, project.classpath)
        val testLoader = forker.newClassLoader(Some(TestInternals.filteredLoader))
        val frameworks = project.testFrameworks.flatMap(f =>
          TestInternals.loadFramework(testLoader, f.names, logger))
        Task.now(Some(DiscoveredTestFrameworks.Jvm(frameworks, forker, testLoader)))
    }
  }

  /**
   * Run the tests for `project` and its dependencies (optional).
   *
   * @param state The current state of Bloop.
   * @param project The project for which to run the tests.
   * @param cwd      The directory in which to start the forked JVM.
   * @param includeDependencies Run test in the dependencies of `project`.
   * @param testFilter A function from a fully qualified class name to a Boolean, indicating whether
   *                   a test must be included.
   * @return The new state of Bloop.
   */
  def test(
      state: State,
      project: Project,
      cwd: AbsolutePath,
      includeDependencies: Boolean,
      frameworkSpecificRawArgs: List[String],
      testFilter: String => Boolean,
      testEventHandler: TestSuiteEventHandler
  ): Task[State] = {
    import state.logger
    def foundFrameworks(frameworks: List[Framework]) = frameworks.map(_.name).mkString(", ")

    // Test arguments coming after `--` can only be used if only one mapping is found
    def considerFrameworkArgs(frameworks: List[Framework]): List[Config.TestArgument] = {
      if (frameworkSpecificRawArgs.isEmpty) Nil
      else {
        frameworks match {
          case Nil => Nil
          case List(oneFramework) =>
            val rawArgs = frameworkSpecificRawArgs
            val cls = oneFramework.getClass.getName
            logger.debug(s"Test options '$rawArgs' assigned to the only found framework $cls'.")
            List(Config.TestArgument(rawArgs, Some(Config.TestFramework(List(cls)))))
          case frameworks =>
            val frameworkNames = foundFrameworks(frameworks)
            val ignoredArgs = frameworkSpecificRawArgs.mkString(" ")
            logger.warn(
              s"Ignored CLI test options '${ignoredArgs}' can only be applied to one framework, found: $frameworkNames")
            Nil
        }
      }
    }

    var failure = false
    val projectsToTest =
      if (!includeDependencies) List(project) else Dag.dfs(state.build.getDagFor(project))
    val testTasks = projectsToTest.filter(_.testFrameworks.nonEmpty).map { project =>
      /* Intercept test failures to set the correct error code */
      val failureHandler = new LoggingEventHandler(state.logger) {
        override def report(): Unit = testEventHandler.report()
        override def handle(event: TestSuiteEvent): Unit = {
          testEventHandler.handle(event)
          event match {
            case TestSuiteEvent.Results(_, ev)
                if ev.exists(e => TestFailedStatus.contains(e.status())) =>
              failure = true
            case _ => ()
          }
        }
      }

      discoverTestFrameworks(project, state).flatMap {
        case None => Task.now(state.mergeStatus(ExitStatus.TestExecutionError))
        case Some(l) =>
          import bloop.util.JavaCompat.EnrichOptional
          if (l.frameworks.isEmpty)
            logger.error("No test frameworks found")
          else
            logger.debug(s"Found test frameworks: ${foundFrameworks(l.frameworks)}")
          val frameworkArgs = considerFrameworkArgs(l.frameworks)
          val args = project.testOptions.arguments ++ frameworkArgs
          logger.debug(s"Running test suites with arguments: $args")
          val lastCompileResult = state.results.lastSuccessfulResult(project)
          val analysis = lastCompileResult.analysis().toOption.getOrElse {
            logger.warn(
              s"Test execution was triggered, but no compilation detected for ${project.name}")
            Analysis.empty
          }
          val discoveredTestSuites =
            discoverTestSuites(state, project, l.frameworks, analysis, testFilter)
          l match {
            case DiscoveredTestFrameworks.Jvm(_, forker, testLoader) =>
              val opts = state.commonOptions
              TestInternals.execute(cwd,
                                    forker,
                                    testLoader,
                                    discoveredTestSuites,
                                    args,
                                    failureHandler,
                                    logger,
                                    opts)
            case DiscoveredTestFrameworks.Js(_, dispose) =>
              Task {
                try {
                  discoveredTestSuites.foreach {
                    case (framework, testSuites) =>
                      testSuites.foreach { testSuite =>
                        val events = TestInternals.executeWithoutForking(framework,
                                                                         Array(testSuite),
                                                                         args,
                                                                         logger)
                        failureHandler.handle(
                          TestSuiteEvent.Results(testSuite.fullyQualifiedName(), events))
                      }
                  }
                  Task.now(ExitStatus.Ok)
                } catch {
                  case t: Throwable =>
                    logger.error(s"An exception was thrown while running the tests: $t")
                    logger.trace(t)
                    Task.now(ExitStatus.TestExecutionError)
                } finally {
                  // TODO It seems that dispose() is called too early
                  //      It logs: [W] TestAdapter.close() was called. Incomplete runs: Set(0, 5, 10, 1, 6, 9, 2, 7, 3, 11, 8, 4)
                  dispose()
                }
              }
          }
      }
    }

    // For now, test execution is only sequential.
    Task.sequence(testTasks).map { exitCodes =>
      // When the test execution is over report no matter what the result is
      testEventHandler.report()
      logger.debug(s"Test suites failed: $failure")
      val isOk = !failure && exitCodes.forall(_ == 0)
      if (isOk) state.mergeStatus(ExitStatus.Ok)
      else state.copy(status = ExitStatus.TestExecutionError)
    }
  }

  /**
   * Runs the fully qualified class `className` in `project`.
   *
   * @param state     The current state of Bloop.
   * @param project   The project to run.
   * @param cwd       The directory in which to start the forked JVM.
   * @param fqn       The fully qualified name of the main class.
   * @param args      The arguments to pass to the main class.
   */
  def runJVM(state: State,
             project: Project,
             cwd: AbsolutePath,
             fqn: String,
             args: Array[String]): Task[State] = {
    val classpath = project.classpath
    val processConfig = Forker(project.javaEnv, classpath)
    val runTask = processConfig.runMain(cwd, fqn, args, state.logger, state.commonOptions)
    runTask.map { exitCode =>
      val exitStatus = Forker.exitStatus(exitCode)
      state.mergeStatus(exitStatus)
    }
  }

  /**
   * Runs the fully qualified class `className` in a Native or JavaScript `project`.
   *
   * @param state     The current state of Bloop.
   * @param project   The project to run.
   * @param cwd       The directory in which to start the forked run process.
   * @param fqn       The fully qualified name of the main class.
   * @param args      The arguments to pass to the main class.
   */
  def runNativeOrJs(
      state: State,
      project: Project,
      cwd: AbsolutePath,
      fqn: String,
      args: Array[String]
  ): Task[State] = {
    Forker.run(cwd, args, state.logger, state.commonOptions).map { exitCode =>
      val exitStatus = Forker.exitStatus(exitCode)
      state.mergeStatus(exitStatus)
    }
  }

  /**
   * Finds the main classes in `project`.
   *
   * @param state   The current state of Bloop.
   * @param project The project for which to find the main classes.
   * @return An array containing all the main classes that were detected.
   */
  def findMainClasses(state: State, project: Project): List[String] = {
    import state.logger
    import bloop.util.JavaCompat.EnrichOptional
    val analysis = state.results.lastSuccessfulResult(project).analysis().toOption match {
      case Some(analysis: Analysis) => analysis
      case _ =>
        logger.warn(s"`Run` is triggered but no compilation detected from '${project.name}'.")
        Analysis.empty
    }

    val mainClasses = analysis.infos.allInfos.values.flatMap(_.getMainClasses).toList
    logger.debug(s"Found ${mainClasses.size} main classes${mainClasses.mkString(": ", ", ", ".")}")
    mainClasses
  }

  def reasonOfInvalidPath(output: Path): Option[String] = {
    if (Files.isDirectory(output))
      Some(s"The output path $output does not point to a file.")
    else if (!Files.isWritable(output.getParent))
      Some(s"The output path ${output.getParent} cannot be created.")
    else None
  }

  def reasonOfInvalidPath(output: Path, extension: String): Option[String] = {
    reasonOfInvalidPath(output).orElse {
      if (!output.toString.endsWith(extension))
        // This is required for the Scala.js linker, otherwise it will throw an exception
        Some(s"The output path $output must have the extension '$extension'.")
      else None
    }
  }

  private[bloop] def pickTestProject(projectName: String, state: State): Option[Project] = {
    state.build.getProjectFor(s"$projectName-test").orElse(state.build.getProjectFor(projectName))
  }

  private[bloop] def discoverTestSuites(
      state: State,
      project: Project,
      frameworks: List[Framework],
      analysis: CompileAnalysis,
      testFilter: String => Boolean
  ): Map[Framework, List[TaskDef]] = {
    import state.logger
    val tests = discoverTests(analysis, frameworks)
    val excluded = project.testOptions.excludes.toSet
    val ungroupedTests = tests.toList.flatMap {
      case (framework, tasks) => tasks.map(t => (framework, t))
    }
    val (includedTests, excludedTests) = ungroupedTests.partition {
      case (_, task) =>
        val fqn = task.fullyQualifiedName()
        !excluded(fqn) && testFilter(fqn)
    }
    if (logger.isVerbose) {
      val allNames = ungroupedTests.map(_._2.fullyQualifiedName).mkString(", ")
      val includedNames = includedTests.map(_._2.fullyQualifiedName).mkString(", ")
      val excludedNames = excludedTests.map(_._2.fullyQualifiedName).mkString(", ")
      logger.debug(s"Bloop found the following tests for ${project.name}: $allNames")
      logger.debug(s"The following tests were included by the filter: $includedNames")
      logger.debug(s"The following tests were excluded by the filter: $excludedNames")
    }
    includedTests.groupBy(_._1).mapValues(_.map(_._2))
  }

  private[bloop] def discoverTests(
      analysis: CompileAnalysis,
      frameworks: List[Framework]
  ): Map[Framework, List[TaskDef]] = {
    import scala.collection.mutable
    val (subclassPrints, annotatedPrints) = TestInternals.getFingerprints(frameworks)
    val definitions = TestInternals.potentialTests(analysis)
    val discovered = Discovery(subclassPrints.map(_._1), annotatedPrints.map(_._1))(definitions)
    val tasks = mutable.Map.empty[Framework, mutable.Buffer[TaskDef]]
    frameworks.foreach(tasks(_) = mutable.Buffer.empty)
    discovered.foreach {
      case (defn, discovered) =>
        TestInternals.matchingFingerprints(subclassPrints, annotatedPrints, discovered).foreach {
          case (_, _, framework, fingerprint) =>
            tasks(framework) += new TaskDef(defn.name, fingerprint, false, Array(new SuiteSelector))
        }
    }
    tasks.mapValues(_.toList).toMap
  }
}
