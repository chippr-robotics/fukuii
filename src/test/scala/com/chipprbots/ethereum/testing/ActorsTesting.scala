package com.chipprbots.ethereum.testing
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.testkit.TestActor.AutoPilot

object ActorsTesting {
  def simpleAutoPilot(makeResponse: PartialFunction[Any, Any]): AutoPilot =
    new AutoPilot {
      def run(sender: ActorRef, msg: Any) = {
        val response = makeResponse.lift(msg)
        response match {
          case Some(value) => sender ! value
          case _           => ()
        }
        this
      }
    }
}
