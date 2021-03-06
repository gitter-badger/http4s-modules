package h4sm.auth
package client

import cats.data.OptionT
import cats.implicits._
import cats.effect.Sync
import h4sm.auth.infrastructure.endpoint.{AuthEndpoints, UserDetail, UserRequest}
import h4sm.auth.infrastructure.repository.persistent.{TokenRepositoryInterpreter, UserRepositoryInterpreter}
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.http4s.dsl._
import org.http4s.client.dsl._
import org.http4s.Uri.uri
import tsec.passwordhashers.jca.BCrypt
import domain.tokens.TokenRepositoryAlgebra
import domain.users.UserRepositoryAlgebra
import doobie.util.transactor.Transactor

class AuthClient[F[_]: Sync : UserRepositoryAlgebra : TokenRepositoryAlgebra]
extends Http4sDsl[F] with Http4sClientDsl[F] {
  val userService = implicitly[UserRepositoryAlgebra[F]]
  val tokenService = implicitly[TokenRepositoryAlgebra[F]]

  implicit val booldecoder : EntityDecoder[F, Boolean] = jsonOf

  val authEndpoints: AuthEndpoints[F, BCrypt] = new AuthEndpoints[F, BCrypt](BCrypt)
  val auth = authEndpoints.endpoints.orNotFound

  def getAuthHeaders(from: Response[F]) : Headers =
    from.headers.filter(_.name.toString.toLowerCase startsWith "authorization")

  def injectAuthHeader(from: Response[F])(to: Request[F]): Request[F] =
    to.withHeaders(getAuthHeaders(from))

  def threadResponse(resp: Response[F])(req: Request[F]): F[Response[F]] = {
    val sessionReq = req.withHeaders(resp.headers.filter(_.name.toString.toLowerCase.startsWith("authorization")))
    auth.run(sessionReq)
  }

  def deleteUser(username: String): F[Unit] = (for {
    u <- userService.byUsername(username)
    (_, uid, _) = u
    _ <- OptionT.liftF(userService.delete(uid))
  } yield ()).getOrElse(())

  def postUser(userRequest: UserRequest): F[Response[F]] = POST(userRequest, uri("/user")).flatMap(auth run _)

  def loginUser(userRequest: UserRequest): F[Response[F]] = POST(userRequest, uri("/login")).flatMap(auth run _)

  // For the client, simply thread the most recent response back into any request that needs
  // authorization. There should probably be a better way to do this, maybe state monad or something.
  def getUser(username: String, continue: Response[F]): Either[ParseFailure, F[Response[F]]] =
    Uri.fromString(s"/user/$username").map(uri => GET(uri).flatMap(threadResponse(continue)(_)))

  def userExists(username : String) : F[Boolean] = 
    Uri.fromString(s"/exists/$username").fold(
      _ => false.pure[F],
      uri => for {
        req <- GET(uri)
        resp <- auth.run(req)
        result <- resp.as[Boolean]
      } yield result
    )

  def getUser(headers : Headers) : F[UserDetail] = for {
    req <- GET(uri("/user"))
    res <- auth.run(req.withHeaders(headers))
    userDetail <- res.as[UserDetail]
  } yield userDetail
}

object AuthClient {
  def fromTransactor[F[_] : Sync](xa : Transactor[F]) : AuthClient[F] = {
    implicit val userService = new UserRepositoryInterpreter[F](xa)
    implicit val tokenService = new TokenRepositoryInterpreter[F](xa)
    new AuthClient[F]
  }
}
