package bloop.bsp

import java.nio.charset.{Charset, StandardCharsets}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import bloop.{Compiler, Project}
import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Action, Dag, Exit, Interpreter, Run, State}
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.BspLogger
import ch.epfl.`scala`.bsp.schema._
import monix.eval.Task
import ch.epfl.scala.bsp.endpoints
import ch.epfl.scala.bsp.schema.ScalaBuildTarget.ScalaPlatform
import com.google.protobuf.ByteString
import org.langmeta.jsonrpc.{
  JsonRpcClient,
  Response => JsonRpcResponse,
  Services => JsonRpcServices
}
import scalapb_circe.JsonFormat
import xsbti.Problem

import scala.concurrent.duration.FiniteDuration

final class BloopBspServices(
    callSiteState: State,
    client: JsonRpcClient,
    relativeConfigPath: RelativePath
) {
  private type ProtocolError = JsonRpcResponse.Error
  private type BspResponse[T] = Task[Either[ProtocolError, T]]

  // Disable ansii codes for now so that the BSP clients don't get unescaped color codes
  private val bspForwarderLogger = BspLogger(callSiteState, client, false)
  final val services = JsonRpcServices.empty
    .requestAsync(endpoints.Build.initialize)(initialize(_))
    .notification(endpoints.Build.initialized)(initialized(_))
    .requestAsync(endpoints.Workspace.buildTargets)(buildTargets(_))
    .requestAsync(endpoints.BuildTarget.dependencySources)(dependencySources(_))
    .requestAsync(endpoints.BuildTarget.scalacOptions)(scalacOptions(_))
    .requestAsync(endpoints.BuildTarget.compile)(compile(_))

  // Internal state, think how to make this more elegant.
  @volatile private var currentState: State = null

  /**
   * Get the latest state that can be reused and cached by bloop so that the next client
   * can have access to it.
   */
  def latestState: State = {
    val state0 = if (currentState == null) callSiteState else currentState
    state0.copy(logger = callSiteState.logger)
  }

  private val pool = callSiteState.pool
  private val defaultOpts = callSiteState.commonOptions
  def reloadState(config: AbsolutePath): Task[State] = {
    bspForwarderLogger.debug(s"Reloading bsp state for ${config.syntax}")
    State.loadActiveStateFor(config, pool, defaultOpts, bspForwarderLogger).map { state0 =>
      state0.copy(logger = bspForwarderLogger, commonOptions = latestState.commonOptions)
    }
  }

  /**
   * Implements the initialize method that is the first pass of the Client-Server handshake.
   *
   * @param params The params request that we get from the client.
   * @return An async computation that returns the response to the client.
   */
  def initialize(params: InitializeBuildParams): BspResponse[InitializeBuildResult] = {
    val configDir = AbsolutePath(params.rootUri).resolve(relativeConfigPath)
    reloadState(configDir).map { state =>
      callSiteState.logger.info("request received: build/initialize")
      currentState = state
      Right(
        InitializeBuildResult(
          Some(
            BuildServerCapabilities(
              compileProvider = true,
              textDocumentBuildTargetsProvider = true,
              dependencySourcesProvider = false,
              buildTargetChangedProvider = true
            )
          )
        )
      )
    }
  }

  val isInitialized = scala.concurrent.Promise[Either[ProtocolError, Unit]]()
  val isInitializedTask = Task.fromFuture(isInitialized.future).memoize
  def initialized(
      initializedBuildParams: InitializedBuildParams
  ): Unit = {
    isInitialized.success(Right(()))
    callSiteState.logger.info("BSP initialization handshake complete.")
  }

  def ifInitialized[T](t: => BspResponse[T]): BspResponse[T] = {
    // Give a time window for `isInitialized` to complete, otherwise assume it didn't happen
    isInitializedTask
      .timeoutTo(
        FiniteDuration(1, TimeUnit.SECONDS),
        Task.now(Left(JsonRpcResponse.invalidRequest("The session has not been initialized.")))
      )
      .flatMap {
        case l: Left[ProtocolError, T] => Task.now(l)
        case r: Right[ProtocolError, T] => t
      }
  }

  type ProjectMapping = (BuildTargetIdentifier, Project)
  private def mapToProjects(
      targets: Seq[BuildTargetIdentifier]): Either[ProtocolError, Seq[ProjectMapping]] = {
    def getProject(target: BuildTargetIdentifier): Either[ProtocolError, ProjectMapping] = {
      val uri = target.uri
      ProjectUris.getProjectDagFromUri(uri, currentState) match {
        case Left(errorMsg) => Left(JsonRpcResponse.parseError(errorMsg))
        case Right(Some(project)) => Right((target, project))
        case Right(None) => Left(JsonRpcResponse.invalidRequest(s"No project associated with $uri"))
      }
    }

    targets.headOption match {
      case Some(head) =>
        val init = getProject(head).map(m => m :: Nil)
        targets.tail.foldLeft(init) {
          case (acc, t) => acc.flatMap(ms => getProject(t).map(m => m :: ms))
        }
      case None =>
        Left(
          JsonRpcResponse.invalidRequest(
            "Empty build targets. Expected at least one build target identifier."))
    }
  }

  def compile(params: CompileParams): BspResponse[CompileReport] = {
    def compile(projects0: Seq[ProjectMapping]): BspResponse[CompileReport] = {
      val current = currentState
      val projects = Dag.reduce(current.build.dags, projects0.map(_._2).toSet)
      val action = projects.foldLeft(Exit(ExitStatus.Ok): Action) {
        case (action, project) => Run(Commands.Compile(project.name), action)
      }

      def report(p: Project, problems: Array[Problem], elapsedMs: Long) = {
        val count = bloop.reporter.Problem.count(problems)
        val id = toBuildTargetId(p)
        CompileReportItem(
          target = Some(id),
          errors = count.errors,
          warnings = count.warnings,
          time = elapsedMs,
          linesOfCode = 0
        )
      }

      Interpreter.execute(action, Task.now(current)).map { state =>
        currentState = state
        val compiledProjects = current.results.diffLatest(state.results)
        val items = compiledProjects.flatMap {
          case (p, result) =>
            result match {
              case Compiler.Result.Empty => Nil
              case Compiler.Result.Cancelled(_) => Nil
              case Compiler.Result.Blocked(_) => Nil
              case Compiler.Result.Failed(problems, elapsed) => List(report(p, problems, elapsed))
              case Compiler.Result.Success(reporter, _, elapsed) =>
                List(report(p, reporter.problems, elapsed))
            }
        }
        Right(CompileReport(items))
      }
    }

    ifInitialized {
      mapToProjects(params.targets) match {
        case Left(error) => Task.now(Left(error))
        case Right(mappings) => compile(mappings)
      }
    }
  }

  private def toBuildTargetId(project: Project): BuildTargetIdentifier =
    BuildTargetIdentifier(project.bspUri)

  def toScalaBuildTarget(project: Project): ScalaBuildTarget = {
    val instance = project.scalaInstance
    val jars = instance.allJars.iterator.map(_.toURI.toString).toList
    ScalaBuildTarget(
      scalaOrganization = instance.organization,
      scalaVersion = instance.version,
      scalaBinaryVersion = instance.version,
      platform = ScalaPlatform.JVM,
      jars = jars
    )
  }

  def buildTargets(request: WorkspaceBuildTargetsRequest): BspResponse[WorkspaceBuildTargets] = {
    ifInitialized {
      val build = currentState.build
      val targets = WorkspaceBuildTargets(
        build.projects.map { p =>
          val id = toBuildTargetId(p)
          val deps = p.dependencies.iterator.flatMap(build.getProjectFor(_).toList)
          val scalaTarget = JsonFormat.toJsonString(toScalaBuildTarget(p))
          val extra = ByteString.copyFrom(scalaTarget, StandardCharsets.UTF_8)
          BuildTarget(
            id = Some(id),
            displayName = p.name,
            languageIds = List("scala", "java"),
            dependencies = deps.map(toBuildTargetId).toList,
            data = extra
          )
        }
      )

      Task.now(Right(targets))
    }
  }

  def dependencySources(request: DependencySourcesParams): BspResponse[DependencySources] = {
    def sources(projects: Seq[ProjectMapping]): BspResponse[DependencySources] = {
      val response = DependencySources(
        projects.map {
          case (target, project) =>
            val sources = project.sources.iterator.map(_.toBspUri).toList
            DependencySourcesItem(Some(target), sources)
        }
      )

      Task.now(Right(response))
    }

    ifInitialized {
      mapToProjects(request.targets) match {
        case Left(error) => Task.now(Left(error))
        case Right(mappings) => sources(mappings)
      }
    }
  }

  def scalacOptions(request: ScalacOptionsParams): BspResponse[ScalacOptions] = {
    def scalacOptions(projects: Seq[ProjectMapping]): BspResponse[ScalacOptions] = {
      val response = ScalacOptions(
        projects.map {
          case (target, project) =>
            ScalacOptionsItem(
              target = Some(target),
              options = project.scalacOptions,
              classpath = project.classpath.iterator.map(_.toBspUri).toList,
              classDirectory = project.classesDir.toBspUri
            )
        }
      )

      Task.now(Right(response))
    }

    ifInitialized {
      mapToProjects(request.targets) match {
        case Left(error) => Task.now(Left(error))
        case Right(mappings) => scalacOptions(mappings)
      }
    }
  }
}

object BloopBspServices {
  private[bloop] final val counter: AtomicInteger = new AtomicInteger(0)
}
