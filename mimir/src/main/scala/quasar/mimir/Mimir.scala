/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.mimir

import slamdata.Predef._
import quasar._
import quasar.common._
import quasar.connector._
import quasar.contrib.matryoshka._
import quasar.contrib.pathy._
import quasar.contrib.scalaz._, eitherT._
import quasar.fp._
import quasar.fp.numeric._
import quasar.fs._
import quasar.fs.mount._
import quasar.qscript._

import quasar.precog.common.Path
import quasar.precog.common.security.APIKey
import quasar.yggdrasil.PathMetadata
import quasar.yggdrasil.vfs.ResourceError

import matryoshka._
import matryoshka.implicits._
import scalaz._, Scalaz._
import scalaz.concurrent.Task

import pathy.Path.{DirName, FileName}

import delorean._

import scala.Predef.implicitly
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object Mimir extends BackendModule {

  // optimistically equal to marklogic's
  type QS[T[_[_]]] =
    QScriptCore[T, ?] :\:
    ThetaJoin[T, ?] :\:
    Const[ShiftedRead[ADir], ?] :/:
    Const[Read[AFile], ?]

  implicit def qScriptToQScriptTotal[T[_[_]]]: Injectable.Aux[QSM[T, ?], QScriptTotal[T, ?]] =
    ::\::[QScriptCore[T, ?]](::\::[ThetaJoin[T, ?]](::/::[T, Const[ShiftedRead[ADir], ?], Const[Read[AFile], ?]]))

  // TODO
  type Repr = Unit
  type M[A] = Task[A]

  def FunctorQSM[T[_[_]]] = Functor[QSM[T, ?]]
  def DelayRenderTreeQSM[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT] = implicitly[Delay[RenderTree, QSM[T, ?]]]
  def ExtractPathQSM[T[_[_]]: RecursiveT] = ExtractPath[QSM[T, ?], APath]
  def QSCoreInject[T[_[_]]] = implicitly[QScriptCore[T, ?] :<: QSM[T, ?]]
  def MonadM = Monad[M]
  def MonadFsErrM = ???
  def PhaseResultTellM = ???
  def PhaseResultListenM = ???
  def UnirewriteT[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT] = implicitly[Unirewrite[T, QS[T]]]
  def UnicoalesceCap[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT] = Unicoalesce.Capture[T, QS[T]]

  type Config = Unit

  def parseConfig(uri: ConnectionUri): EitherT[Task, ErrorMessages, Config] =
    ().point[EitherT[Task, ErrorMessages, ?]]

  def compile(cfg: Config): FileSystemDef.DefErrT[Task, (M ~> Task, Task[Unit])] =
    ((reflNT[Task], ().point[Task])).point[FileSystemDef.DefErrT[Task, ?]]

  val Type = FileSystemType("mimir")

  def plan[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT](
      cp: T[QSM[T, ?]]): Backend[Repr] = ???

  object QueryFileModule extends QueryFileModule {
    import QueryFile._

    def executePlan(repr: Repr, out: AFile): Backend[AFile] = ???
    def evaluatePlan(repr: Repr): Backend[ResultHandle] = ???
    def more(h: ResultHandle): Backend[Vector[Data]] = ???
    def close(h: ResultHandle): Configured[Unit] = ???
    def explain(repr: Repr): Backend[String] = ???

    def listContents(dir: ADir): Backend[Set[PathSegment]] = {
      val path: Path = Path(pathy.Path.posixCodec.printPath(dir))

      def toSegment: PathMetadata => PathSegment = {
        case PathMetadata(path, PathMetadata.DataDir(_)) => DirName(path.path).left[FileName]
        case PathMetadata(path, PathMetadata.DataOnly(_)) => FileName(path.path).right[DirName]
        case PathMetadata(path, PathMetadata.PathOnly) => sys.error(s"found path $path")
      }

      def children(apiKey: APIKey): EitherT[Future, ResourceError, Set[PathSegment]] =
        Precog.vfs.findDirectChildren(apiKey, path).map(_.map(toSegment))

      def toFSError: ResourceError => FileSystemError = {
        case ResourceError.Corrupt(msg) => ???
        case ResourceError.IOError(ex) => ???
        case ResourceError.IllegalWriteRequestError(msg) => ???
        case ResourceError.PermissionsError(msg) => ???
        case ResourceError.NotFound(msg) => ???
        case ResourceError.ResourceErrors(errs) => ???
      }

      val segments: FileSystemErrT[Future, Set[PathSegment]] =
        for {
          key <- Precog.RootAPIKey.liftM[FileSystemErrT]
          stuff <- children(key).leftMap(toFSError)
        } yield stuff

      val futureToTask: FileSystemErrT[Future, ?] ~> FileSystemErrT[Task, ?] =
        Hoist[FileSystemErrT].hoist[Future, Task](λ[Future ~> Task](_.toTask))

      val result: FileSystemErrT[Task, ?] ~> Backend =
        Hoist[FileSystemErrT].hoist[Task, PhaseResultT[Configured, ?]](
          liftMT[Configured, PhaseResultT] compose liftMT[Task, ConfiguredT])

      result.apply(futureToTask.apply(segments))
    }

    def fileExists(file: AFile): Configured[Boolean] = ???
  }

  object ReadFileModule extends ReadFileModule {
    import ReadFile._

    def open(file: AFile, offset: Natural, limit: Option[Positive]): Backend[ReadHandle] = ???
    def read(h: ReadHandle): Backend[Vector[Data]] = ???
    def close(h: ReadHandle): Configured[Unit] = ???
  }

  object WriteFileModule extends WriteFileModule {
    import WriteFile._

    def open(file: AFile): Backend[WriteHandle] = ???
    def write(h: WriteHandle, chunk: Vector[Data]): Configured[Vector[FileSystemError]] = ???
    def close(h: WriteHandle): Configured[Unit] = ???
  }

  object ManageFileModule extends ManageFileModule {
    import ManageFile._

    def move(scenario: MoveScenario, semantics: MoveSemantics): Backend[Unit] = ???
    def delete(path: APath): Backend[Unit] = ???
    def tempFile(near: APath): Backend[AFile] = ???
  }
}
