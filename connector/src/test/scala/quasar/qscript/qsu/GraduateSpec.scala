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

package quasar.qscript.qsu

import slamdata.Predef._

import quasar.Qspec
import quasar.Planner.PlannerError
import quasar.contrib.pathy.AFile
import quasar.fp._
import quasar.qscript.construction
import quasar.qscript.HoleF
import quasar.qscript.MapFuncsCore.IntLit

import matryoshka.EqualT
import matryoshka.data.Fix, Fix._
import matryoshka.implicits._
import org.specs2.matcher.{Expectable, Matcher, MatchResult}
import pathy.Path, Path.{file, Sandboxed}
import scalaz.{\/, EitherT, Need, StateT}
import scalaz.Scalaz._

object GraduateSpec extends Qspec with QSUTTypes[Fix] {

  type F[A] = EitherT[StateT[Need, Long, ?], PlannerError, A]

  type QSU[A] = QScriptUniform[A]
  type QSE[A] = QScriptEducated[A]

  val grad = Graduate[Fix]

  val qsu = QScriptUniform.Dsl[Fix]
  val qse = construction.Dsl[Fix, QSE, Fix[QSE]](_.embed)
  val func = construction.Func[Fix]

  val root = Path.rootDir[Sandboxed]
  val afile: AFile = root </> file("foobar")

  "graduating QSU to QScript" should {

    "convert Read" in {
      val qgraph: Fix[QSU] = qsu.read(afile)
      val qscript: Fix[QSE] = qse.Read[AFile](afile)

      qgraph must graduateAs(qscript)
    }

    "convert Map" in {
      val fm: FreeMap = func.Add(HoleF, IntLit(17))
      val qgraph: Fix[QSU] = qsu.map(qsu.read(afile), fm)
      val qscript: Fix[QSE] = qse.Map(qse.Read[AFile](afile), fm)

      qgraph must graduateAs(qscript)
    }
  }

  def graduateAs(expected: Fix[QSE]): Matcher[Fix[QSU]] = {
    new Matcher[Fix[QSU]] {
      def apply[S <: Fix[QSU]](s: Expectable[S]): MatchResult[S] = {
        val actual: PlannerError \/ Fix[QSE] =
          evaluate(grad[F](QSUGraph.fromTree[Fix](s.value)))

        actual.bimap[MatchResult[S], MatchResult[S]](
        { err =>
          failure(s"graduating produced planner error: ${err.shows}", s)
        },
        { qscript =>
          result(EqualT[Fix].equal[QSE](qscript, expected), "yay", "boo", s)
        }).merge
      }
    }
  }

  def evaluate[A](fa: F[A]): PlannerError \/ A = fa.run.eval(0L).value
}