package sttp.client

import scala.concurrent.Future

object quick extends SttpApi {
  implicit lazy val backend: SttpBackend[Future, Nothing, NothingT] = FetchBackend()
}
