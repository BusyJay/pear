package com.busyjay

import akka.actor.{Actor, ActorLogging}
import akka.persistence.{PersistentActor, SnapshotOffer}
import com.busyjay.Pear._

import scala.collection.mutable

object Pear {
  final case class Repository(owner: String, name: String)
  final case class PullRequest(repository: Repository, id: Int)

  case class Db(registry: mutable.LinkedHashMap[PullRequest, Int]) {
    def merge(pr: PullRequest, priority: Int): Unit = registry += ((pr, priority))
    def onMerged(pr: PullRequest): Unit = registry -= pr
    def nextJob(): Option[PullRequest] = if (registry.isEmpty) None else Some(registry.maxBy(_._2)._1)
    def precedes(base: PullRequest): Option[List[PullRequest]] = {
      var met = false
      val priority = registry.get(base) match {
        case Some(p) => p
        case None => return None
      }
      Some(registry.filter {
        case (job, p) => if (p > priority) {
          true
        } else if (p == priority) {
          if (job == base) {
            met = true
          }
          !met
        } else {
          false
        }
      }.keys.toList)
    }
    def cancel(pr: PullRequest): Boolean = registry.remove(pr).isDefined
  }

  final case class Watch(repository: Repository)
  final case class Merge(pr: PullRequest, priority: Int)
  final case class OnMerged(pr: PullRequest)
  final case class Vet(pr: PullRequest)
}

class Pear extends PersistentActor with ActorLogging {
  var db = Db(mutable.LinkedHashMap())

  override def persistenceId: String = "pear"

  override def receiveRecover: Receive = {
    case SnapshotOffer(_, snapshot: Db) => db = snapshot
    case Merge(pr, priority) => db merge (pr, priority)
    case OnMerged(pr) => db onMerged pr
  }

  override def receiveCommand: Receive = {
    case e @ Merge(_, _) => persist(e) { case Merge(pr, priority) =>
      db merge(pr, priority)
      context.system.eventStream.publish(e)
      if (lastSequenceNr % 1024 == 1) {
        saveSnapshot(db)
      }
    }
    case e @ OnMerged(_) => persist(e) { case OnMerged(pr) =>
      db onMerged pr
      context.system.eventStream.publish(e)
      if (lastSequenceNr % 1024 == 1) {
        saveSnapshot(db)
      }
    }
    case Vet(pr) => {

    }
  }
}
