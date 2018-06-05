package com.busyjay

import akka.actor.{Actor, ActorLogging, Props, Status, Timers}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, _}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.pattern.pipe
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import spray.json._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Failure

object GitHubResolverActor {
  import Pear.{PullRequest, Repository}

  final case class QueryPullRequest(pr: PullRequest)
  final case class PullRequestState(merged: Boolean, upToDate: Boolean, approved: Boolean)

  final case class QueryUser(user: String, repository: Repository)
  final case class UserPermission(writable: Boolean)

  final case class Subscribe(repository: Repository)
  final case class Notification(pr: PullRequest, user: String, comment: String)

  final case class Merge(pr: PullRequest)
  final case object Merged

  final case object NotificationPoll
  final case class Refreshed(pollInterval: FiniteDuration, body: Option[JsValue])
  final case Failure

  def props: Props = Props[GitHubResolverActor]
}

class GitHubResolverActor extends Actor with Timers with ActorLogging {
  import GitHubResolverActor._
  import context.dispatcher

  private val config = context.system.settings.config

  private val token = if (config.hasPath("pear.token")) config.getString("pear.token") else ""
  private val enabledRepositories = context.system.settings.config.getStringList("pear.repos")
  var lastQueryTime: DateTime = DateTime.MinValue
  var pollInterval: FiniteDuration = 1 minute
  var refreshing = false
  val http = Http(context.system)

  final implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(context.system))

  timers.startSingleTimer(NotificationPoll, NotificationPoll, 1 second)

  def api(path: String): String = s"https://api.github.com$path"

  def handleRequest[U](req: HttpRequest)(f: (HttpResponse, Option[JsValue]) => U): Future[U] = {
    http.singleRequest(if (token.nonEmpty) {
      req.addHeader(Authorization(OAuth2BearerToken(token)))
    } else req) flatMap (res =>
    {
      if (res.status.isFailure()) {
        log.error("request failed: [code {}] {}", res.status, res.entity.withSizeLimit(1000).toString)
        res.discardEntityBytes()
        Future.successful(f(res, None))
      } else {
        val body = Unmarshal(res.entity).to[String]
        body map (v => f(res, Some(v.parseJson)))
      }
    }) pipeTo self
  }

  override def receive: Receive = {
    case NotificationPoll => {
      val now = DateTime.now
      if (now > lastQueryTime + pollInterval.toMillis && !refreshing) {
        log.debug("refreshing at {}", now)
        refreshing = true
        handleRequest(HttpRequest(uri = api("/notifications")).addHeader(`If-Modified-Since`(lastQueryTime))) ((resp, v) => {
          val newInterval = resp.headers.find(_.name() == "X-Poll-Interval").map(h => Integer.parseInt(h.value())).getOrElse(60).seconds
          if (resp.status == StatusCodes.NotModified) {
            Refreshed(newInterval, None)
          } else {
            Refreshed(newInterval, v)
          }
        })
      }
    }
    case Refreshed(newInterval, v) => {
      refreshing = false
      pollInterval = newInterval
      println(v)
    }
  }
}
