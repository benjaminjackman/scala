/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$

package scala.actors

import scala.collection.mutable.HashMap

/**
 * This class is used by our efficient message queue
 * implementation.
 *
 * @author Philipp Haller
 */
@serializable @SerialVersionUID(7124278808020037465L)
@deprecated("this class is going to be removed in a future release")
class MessageQueueElement(msg: Any, session: OutputChannel[Any], next: MessageQueueElement) extends MQueueElement(msg, session, next) {
  def this() = this(null, null, null)
  def this(msg: Any, session: OutputChannel[Any]) = this(msg, session, null)
}

private[actors] class MQueueElement(val msg: Any, val session: OutputChannel[Any], var next: MQueueElement) {
  def this() = this(null, null, null)
  def this(msg: Any, session: OutputChannel[Any]) = this(msg, session, null)
}

/**
 * The class <code>MessageQueue</code> provides an efficient
 * implementation of a message queue specialized for this actor
 * library. Classes in this package are supposed to be the only
 * clients of this class.
 *
 * @author Philipp Haller
 */
@serializable @SerialVersionUID(2168935872884095767L)
@deprecated("this class is going to be removed in a future release")
class MessageQueue(label: String) extends MQueue(label)

private[actors] class MQueue(protected val label: String) {
  protected var first: MQueueElement = null
  protected var last: MQueueElement = null  // last eq null iff list is empty
  private var _size = 0
  
  private val queueMap = new HashMap[Class[T] forSome { type T }, MQueue]

  def size = _size
  final def isEmpty = last eq null

  protected def changeSize(diff: Int) {
    _size += diff
  }

  def append(msg: Any, session: OutputChannel[Any], trans: Boolean) {
    if (trans) {
      val msgClass = msg.asInstanceOf[AnyRef].getClass
      queueMap.get(msgClass) match {
        case None =>
          val msgQueue = new MQueue(msgClass.toString)
          msgQueue.append(msg, session)
          queueMap += (msgClass -> msgQueue)

        case Some(queue) =>
          queue.append(msg, session)
      }
    } else
      append(msg, session)
  }

  def append(msg: Any, session: OutputChannel[Any]) {
    changeSize(1) // size always increases by 1
    val el = new MQueueElement(msg, session)

    if (isEmpty) first = el
    else last.next = el
    
    last = el
  }

  def foreach(f: (Any, OutputChannel[Any]) => Unit) {
    var curr = first
    while (curr != null) {
      f(curr.msg, curr.session)
      curr = curr.next
    }
  }

  def foldLeft[B](z: B)(f: (B, Any) => B): B = {
    var acc = z
    var curr = first
    while (curr != null) {
      acc = f(acc, curr.msg)
      curr = curr.next
    }
    acc
  }

  /** Returns the n-th message that satisfies the predicate <code>p</code>
   *  without removing it.
   */
  def get(n: Int)(p: Any => Boolean): Option[Any] = {
    var pos = 0
    
    def test(msg: Any): Boolean =
      p(msg) && (pos == n || { pos += 1; false })
      
    var curr = first
    while (curr != null)
      if (test(curr.msg)) return Some(curr.msg) // early return
      else curr = curr.next

    None
  }

  /** Removes the n-th message that satisfies the predicate <code>p</code>.
   */
  def remove(n: Int)(p: (Any, OutputChannel[Any]) => Boolean): Option[(Any, OutputChannel[Any])] =
    removeInternal(p)(n) map (x => (x.msg, x.session))
    
  /** Extracts the first message that satisfies the predicate <code>p</code>
   *  or <code>null</code> if <code>p</code> fails for all of them.
   */
  def extractFirst(p: (Any, OutputChannel[Any]) => Boolean): MQueueElement =
    removeInternal(p)(0) orNull

  def extractFirst(pf: Any =>? Any): MQueueElement =
    removeInternal(pf)(0) orNull

/*
  def extractFirst(p: (Any, OutputChannel[Any]) => Boolean, tf: TranslucentFunction[Any, Nothing]): MQueueElement =
    removeInternal(p, tf)(0) orNull
*/

/*
  private def removeInternal(p: (Any, OutputChannel[Any]) => Boolean, tf: TranslucentFunction[Any, Nothing])(n: Int): Option[MQueueElement] = {
    val msgClass = p._1.getClass
    removeInternal(p)(n)
  }
*/

  private def removeInternal(pf: Any =>? Any)(n: Int): Option[MQueueElement] =
/*
    if (pf.isInstanceOf[TranslucentFunction[Any, Any]]) {
      // iterate over classes for which function is defined
      val transFun = pf.asInstanceOf[TranslucentFunction[Any, Any]]
      val iter = transFun.definedFor.iterator
      var finished = false
      var res: Option[MQueueElement] = None
      while (!finished && iter.hasNext) {
        val clazz = iter.next
        queueMap.get(clazz) match {
          case None =>
            // next iteration
          case Some(queue) =>
            val found = 
          
        }
      }

    } else*/ removeInternal((msg: Any, out: OutputChannel[Any]) => pf.isDefinedAt(msg))(n)

  private def removeInternal(p: (Any, OutputChannel[Any]) => Boolean)(n: Int): Option[MQueueElement] = {
    var pos = 0

    def foundMsg(x: MQueueElement) = {        
      changeSize(-1)
      Some(x)
    }
    def test(msg: Any, session: OutputChannel[Any]): Boolean =
      p(msg, session) && (pos == n || { pos += 1 ; false })

    if (isEmpty)    // early return
      return None
    
    // special handling if returning the head
    if (test(first.msg, first.session)) {
      val res = first
      first = first.next
      if (res eq last)
        last = null
      
      foundMsg(res)
    }
    else {
      var curr = first.next   // init to element #2
      var prev = first
      
      while (curr != null) {
        if (test(curr.msg, curr.session)) {
          prev.next = curr.next
          if (curr eq last)
            last = prev
            
          return foundMsg(curr) // early return
        }
        else {
          prev = curr
          curr = curr.next
        }
      }
      // not found
      None
    }
  }
}

/** Debugging trait.
 */
private[actors] trait MessageQueueTracer extends MQueue
{
  private val queueNumber = MessageQueueTracer.getQueueNumber

  override def append(msg: Any, session: OutputChannel[Any]) {
    super.append(msg, session)
    printQueue("APPEND %s" format msg)
  }
  override def get(n: Int)(p: Any => Boolean): Option[Any] = {
    val res = super.get(n)(p)
    printQueue("GET %s" format res)
    res
  }
  override def remove(n: Int)(p: (Any, OutputChannel[Any]) => Boolean): Option[(Any, OutputChannel[Any])] = {
    val res = super.remove(n)(p)
    printQueue("REMOVE %s" format res)
    res
  }
  override def extractFirst(p: (Any, OutputChannel[Any]) => Boolean): MQueueElement = {
    val res = super.extractFirst(p)
    printQueue("EXTRACT_FIRST %s" format res)
    res
  }
  
  private def printQueue(msg: String) = {
    def firstMsg = if (first eq null) "null" else first.msg
    def lastMsg = if (last eq null) "null" else last.msg

    println("[%s size=%d] [%s] first = %s, last = %s".format(this, size, msg, firstMsg, lastMsg))
  }
  override def toString() = "%s:%d".format(label, queueNumber)
}

object MessageQueueTracer {
  // for tracing purposes
  private var queueNumberAssigner = 0
  private def getQueueNumber = synchronized {
    queueNumberAssigner += 1
    queueNumberAssigner
  }
}
