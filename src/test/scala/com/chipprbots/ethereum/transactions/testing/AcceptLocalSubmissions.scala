package com.chipprbots.ethereum.transactions.testing

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.testkit.TestActor.AutoPilot

import com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.SubmitLocalTransaction

/** A pool stand-in that accepts every local submission: the services ask the pool with [[SubmitLocalTransaction]] and
  * wait for its decision, so a probe standing in for the pool must answer it. The message still reaches the probe, for
  * the test to inspect.
  */
object AcceptLocalSubmissions extends AutoPilot:

  def run(sender: ActorRef, msg: Any): AutoPilot =
    msg match
      case submit: SubmitLocalTransaction => submit.replyTo ! Right(())
      case _                              => ()
    this

  /** The same for a Typed probe: every command is forwarded to `probe`, and local submissions are accepted. */
  def forwardingTo(probe: org.apache.pekko.actor.typed.ActorRef[Command]): Behavior[Command] =
    Behaviors.receiveMessage { msg =>
      probe ! msg
      msg match
        case submit: SubmitLocalTransaction => submit.replyTo ! Right(())
        case _                              => ()
      Behaviors.same
    }
