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

package quasar.physical.marklogic

import quasar.Predef._
import quasar.Data
import quasar.effect._

import com.marklogic.xcc._
import scalaz._, Scalaz._

object testing {
  import xcc._, xquery._, fs._

  /** Returns the results, as `Data`, of evaluating the module or `None` if
    * evaluation succeded without producing any results.
    */
  def moduleResults[F[_]: Monad: Capture: CSourceReader](main: MainModule): F[ErrorMessages \/ Option[Data]] = {
    type G[A] = ReaderT[EitherT[F, XccError, ?], Session, A]

    val result = for {
      qr <- session.evaluateModule_[G](main)
      rs <- qr.toImmutableArray[G]
    } yield rs.headOption traverse xdmitem.toData[ErrorMessages \/ ?] _

    (contentsource.defaultSession[EitherT[F, XccError, ?]] >>= result)
      .leftMap(_.shows.wrapNel)
      .run.map(_.join)
  }
}