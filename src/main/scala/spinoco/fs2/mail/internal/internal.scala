package spinoco.fs2.mail

import fs2.{Pipe, Stream}
import fs2.util.Async
import fs2.util.syntax._

package object internal {

  /**
    * Behaves like [[Stream.takeThrough]], but resulting stream will not end until `f` yields to false,
    * draining all elements where `f` still yields to true.
    *
    * This is done by running the source stream in parallel to the consume stream. The messages read from the
    * source stream are then published into a synchronous queue, that blocks until the down stream reads from it.
    *
    * Once downstream finishes this synchronous queue is read until exhaustion marked by [[None]].
    */
  def takeThroughDrain[F[_], A](predicate: A => Boolean)(
    implicit F: Async[F]
  ): Pipe[F, A, A] = { source =>
    Stream.eval(fs2.async.synchronousQueue[F, Option[A]]).flatMap{ feedQueue =>

    // Finished flag, to prevent reading of the synchronous queue in case
    // the down stream consumed all data available to it.
    Stream.eval(F.refOf(false)).flatMap{ finishedRef =>
      Stream.eval(F.start(
        source.takeThrough(predicate)
        .evalMap( a => feedQueue.enqueue1(Some(a)))
        .onFinalize(finishedRef.setPure(true) >> feedQueue.enqueue1(None))
        .run
      )).flatMap(_ =>
        feedQueue.dequeue.unNoneTerminate
      ).onFinalize{
        finishedRef.get.flatMap{
          case true => F.pure(())
          case false => feedQueue.dequeue.unNoneTerminate.drain.run
        }
      }
    }}
  }

}