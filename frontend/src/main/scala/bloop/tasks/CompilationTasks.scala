package bloop.tasks

import java.util.Optional

import bloop.{CompileInputs, CompilerCache, Project, Compiler}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import xsbti.Logger
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}
import bloop.util.{Progress, TopologicalSort}
import sbt.internal.inc.{ConcreteAnalysisContents, FileAnalysisStore}

class CompilationTasks(initialProjects: Map[String, Project],
                       cache: CompilerCache,
                       logger: Logger) {
  private final val EmptyCompileResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  def clean(projectNames: List[String]): Map[String, Project] = {
    initialProjects.filterKeys(projectNames.contains).mapValues { p =>
      p.copy(previousResult = EmptyCompileResult)
    }
  }

  def persistAnalysis(project: Project, logger: Logger): Unit = {
    import bloop.util.JavaCompat.{EnrichOptional, toSupplier}
    val previousResult = project.previousResult
    (previousResult.analysis().toOption, previousResult.setup().toOption) match {
      case (Some(analysis), Some(setup)) =>
        project.origin match {
          case Some(origin) =>
            val storeFile = origin.getParent.resolve(s"${project.name}-analysis.bin").toFile
            FileAnalysisStore.binary(storeFile).set(ConcreteAnalysisContents(analysis, setup))
          case None => logger.warn(s"Missing target directory for ${project.name}.")
        }
      case _ => logger.debug(s"Project ${project.name} has no analysis file.")
    }
  }

  def sequential(project: Project)(implicit ec: ExecutionContext): Map[String, Project] = {
    val progress = new Progress
    val subTasks: Map[String, Task[Map[String, Project]]] =
      getTasks(project, progress)
    subTasks.toSeq.sliding(2).foreach {
      case Seq((_, fst), (_, snd)) => snd.dependsOn(fst)
      case single                  => ()
    }
    initialProjects ++ execute(subTasks(project.name), logger)
  }

  def parallel(project: Project)(implicit ec: ExecutionContext): Map[String, Project] = {
    val progress = new Progress
    val subTasks = getTasks(project, progress)
    subTasks.foreach {
      case (name, task) =>
        val dependencies = initialProjects(name).dependencies
        dependencies.foreach(dep => task.dependsOn(subTasks(dep)))
    }
    initialProjects ++ execute(subTasks(project.name), logger)
  }

  def parallelNaive(project: Project)(implicit ec: ExecutionContext): Map[String, Project] = {
    val progress = new Progress
    val steps = TopologicalSort.tasks(project, initialProjects)

    progress.setTotal(steps.flatten.size)
    val changedProjects = for {
      tasks <- steps
      project <- tasks.par
    } yield execute(getTask(project, progress), logger)

    val mergeable = implicitly[Mergeable[Map[String, Project]]]
    initialProjects ++ mergeable.merge(changedProjects).toMap
  }

  private def execute(task: Task[Map[String, Project]], logger: Logger)(
      implicit ec: ExecutionContext): Map[String, Project] = {
    Await.result(task.run(), Duration.Inf) match {
      case Task.Success(result) => result
      case Task.Failure(partial, reasons) =>
        reasons.foreach(throwable => logger.trace(() => throwable))
        partial
    }
  }

  private def getTasks(project: Project,
                       progress: Progress): Map[String, Task[Map[String, Project]]] = {
    val toCompile = TopologicalSort.reachable(project, initialProjects)
    progress.setTotal(toCompile.size)
    toCompile.map {
      case (name, proj) => name -> getTask(proj, progress)
    }
  }

  private def getTask(project: Project, progress: Progress): Task[Map[String, Project]] = {
    new Task(projects => doCompile(projects, project), () => progress.update())
  }

  private def doCompile(previousProjects: Map[String, Project],
                        project: Project): Map[String, Project] = {
    val inputs = toCompileInputs(project)
    val result = Compiler.compile(inputs)
    val previousResult =
      PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
    previousProjects ++ Map(project.name -> project.copy(previousResult = previousResult))
  }

  def toCompileInputs(project: Project): CompileInputs = {
    val instance = project.scalaInstance
    val sourceDirs = project.sourceDirectories
    val classpath = project.classpath
    val classesDir = project.classesDir
    val target = project.tmp
    val scalacOptions = project.scalacOptions
    val javacOptions = project.javacOptions
    val previous = project.previousResult
    CompileInputs(instance,
                  cache,
                  sourceDirs,
                  classpath,
                  classesDir,
                  target,
                  scalacOptions,
                  javacOptions,
                  previous,
                  logger)
  }
}