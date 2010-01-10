/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$

package scala.actors

import scala.actors.scheduler.{DelegatingScheduler, DefaultThreadPoolScheduler}
import scala.collection.mutable.Queue

private object Reactor {
  val scheduler = new DelegatingScheduler {
    def makeNewScheduler: IScheduler = {
      val s = new DefaultThreadPoolScheduler(false)
      Debug.info(this+": starting new "+s+" ["+s.getClass+"]")
      s.start()
      s
    }
  }
}

/**
 * The Reactor trait provides lightweight actors.
 *
 * @author Philipp Haller
 */
trait Reactor extends OutputChannel[Any] {

  /* The actor's mailbox. */
  /*private[actors]*/ val mailbox = new TransMQueue("Reactor")

  // guarded by this
  private[actors] val sendBuffer = new Queue[(Any, OutputChannel[Any])]

  /* If the actor waits in a react, continuation holds the
   * message handler that react was called with.
   */
  @volatile
  private[actors] var continuation: Any =>? Unit = null

  /* Whenever this Actor executes on some thread, waitingFor is
   * guaranteed to be equal to waitingForNone.
   *
   * In other words, whenever waitingFor is not equal to
   * waitingForNone, this Actor is guaranteed not to execute on some
   * thread.
   */
  private[actors] val waitingForNone = new TranslucentFunctionWrapper[Any, Unit](new PartialFunction[Any, Unit] {
    def isDefinedAt(x: Any) = false
    def apply(x: Any) {}
  }, List())

  // guarded by lock of this
  private[actors] var waitingFor: TranslucentFunction[Any, Any] = waitingForNone

  /**
   * The behavior of an actor is specified by implementing this
   * abstract method.
   */
  def act(): Unit

  protected[actors] def exceptionHandler: Exception =>? Unit =
    Map()

  protected[actors] def scheduler: IScheduler =
    Reactor.scheduler

  protected[actors] def mailboxSize: Int =
    mailbox.size

  /**
   * Sends <code>msg</code> to this actor (asynchronous) supplying
   * explicit reply destination.
   *
   * @param  msg      the message to send
   * @param  replyTo  the reply destination
   */
  def send(msg: Any, replyTo: OutputChannel[Any]) {
    val todo = synchronized {
      if (waitingFor ne waitingForNone) {
        val savedWaitingFor = waitingFor
        waitingFor = waitingForNone
        startSearch(msg, replyTo, savedWaitingFor)
      } else {
        sendBuffer.enqueue((msg, replyTo))
        () => { /* do nothing */ }
      }
    }
    todo()
  }

  private[actors] def startSearch(msg: Any, replyTo: OutputChannel[Any], handler: TranslucentFunction[Any, Any]) =
    () => scheduler execute (makeReaction(() => {
      val startMbox = new TransMQueue("Start")
      synchronized { startMbox.appendTagged(msg, replyTo) }
      searchMailbox(startMbox, handler, true)
    }))

  private[actors] def makeReaction(fun: () => Unit): Runnable =
    new ReactorTask(this, fun)

  /* Note that this method is called without holding a lock.
   * Therefore, to read an up-to-date continuation, it must be @volatile.
   */
  private[actors] def resumeReceiver(item: (Any, OutputChannel[Any]), onSameThread: Boolean) {
    // assert continuation != null
    if (onSameThread)
      continuation(item._1)
    else {
      scheduleActor(continuation, item._1)
      /* Here, we throw a SuspendActorException to avoid
         terminating this actor when the current ReactorTask
         is finished.

         The SuspendActorException skips the termination code
         in ReactorTask.
       */
      throw Actor.suspendException
    }
  }

  def !(msg: Any) {
    send(msg, null)
  }

  def forward(msg: Any) {
    send(msg, null)
  }

  def receiver: Actor = this.asInstanceOf[Actor]

  // guarded by this
  private[actors] def drainSendBuffer(mbox: TransMQueue) {
    while (!sendBuffer.isEmpty) {
      val item = sendBuffer.dequeue()
      mbox.appendTagged(item._1, item._2)
    }
  }

  // assume continuation != null
  private[actors] def searchMailbox(startMbox: TransMQueue,
                                    handler: TranslucentFunction[Any, Any],
                                    resumeOnSameThread: Boolean) {
    var tmpMbox = startMbox
    var done = false
    while (!done) {
      val qel = tmpMbox.extractFirst(handler)
      if (tmpMbox ne mailbox)
        tmpMbox.foreachTagged((m, s) => mailbox.appendTagged(m, s))
      if (null eq qel) {
        synchronized {
          // in mean time new stuff might have arrived
          if (!sendBuffer.isEmpty) {
            tmpMbox = new TransMQueue("Temp")
            drainSendBuffer(tmpMbox)
            // keep going
          } else {
            waitingFor = handler
            /* Here, we throw a SuspendActorException to avoid
               terminating this actor when the current ReactorTask
               is finished.

               The SuspendActorException skips the termination code
               in ReactorTask.
             */
            throw Actor.suspendException
          }
        }
      } else {
        resumeReceiver((qel.msg, qel.session), resumeOnSameThread)
        done = true
      }
    }
  }

  protected[actors] def react(f: TranslucentFunction[Any, Unit]): Nothing = {
    assert(Actor.rawSelf(scheduler) == this, "react on channel belonging to other actor")
    synchronized { drainSendBuffer(mailbox) }
    continuation = f
    searchMailbox(mailbox, f, false)
    throw Actor.suspendException
  }

  /* This method is guaranteed to be executed from inside
   * an actors act method.
   *
   * assume handler != null
   *
   * never throws SuspendActorException
   */
  private[actors] def scheduleActor(handler: Any =>? Unit, msg: Any) = {
    val fun = () => handler(msg)
    val task = new ReactorTask(this, fun)
    scheduler executeFromActor task
  }

  def start(): Reactor = {
    scheduler.newActor(this)
    val task = new ReactorTask(this, () => act())
    scheduler execute task
    this
  }

  /* This closure is used to implement control-flow operations
   * built on top of `seq`. Note that the only invocation of
   * `kill` is supposed to be inside `Reaction.run`.
   */
  @volatile
  private[actors] var kill: () => Unit =
    () => { exit() }

  private[actors] def seq[a, b](first: => a, next: => b): Unit = {
    val s = Actor.rawSelf(scheduler)
    val killNext = s.kill
    s.kill = () => {
      s.kill = killNext

      // to avoid stack overflow:
      // instead of directly executing `next`,
      // schedule as continuation
      scheduleActor({ case _ => next }, 1)
      throw Actor.suspendException
    }
    first
    throw new KillActorException
  }

  protected[this] def exit(): Nothing = {
    terminated()
    throw Actor.suspendException
  }

  private[actors] def terminated() {
    scheduler.terminated(this)
  }

}
