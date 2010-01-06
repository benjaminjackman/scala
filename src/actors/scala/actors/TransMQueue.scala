/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2005-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id: MessageQueue.scala 20137 2009-12-15 14:23:33Z extempore $

package scala.actors

import scala.collection.mutable.HashMap

private[actors] class TransMQueueElement(val msg: Any, val session: OutputChannel[Any], val time: Int, var next: TransMQueueElement) {
  def this(msg: Any, session: OutputChannel[Any], time: Int) = this(msg, session, time, null)
}

private[actors] class TransMQueue(protected val label: String) {
  protected var first: TransMQueueElement = null
  protected var last: TransMQueueElement = null  // last eq null iff list is empty
  private var _size = 0
  
  private val queueMap = new HashMap[Class[T] forSome { type T }, TransMQueue]

  private var cnt = 0
  def nextTime = { cnt += 1; cnt }

  def size = _size
  final def isEmpty = last eq null

  protected def changeSize(diff: Int) {
    _size += diff
  }

  def appendTagged(msg: Any, session: OutputChannel[Any]) {
    val msgClass = msg.asInstanceOf[AnyRef].getClass
//    println("appending (class of "+msg+" is "+msgClass+")")
    queueMap.get(msgClass) match {
      case None =>
        val msgQueue = new TransMQueue(msgClass.toString)
        msgQueue.append(msg, session)
        queueMap += (msgClass -> msgQueue)

      case Some(queue) =>
        queue.append(msg, session)
    }
  }

  def printStats() {
    queueMap.keys.foreach { key =>
      val box = queueMap(key)
      println(key+":"+box.size)
                         }
  }

  def append(msg: Any, session: OutputChannel[Any]) {
    changeSize(1) // size always increases by 1
    val el = new TransMQueueElement(msg, session, nextTime)

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
/*
  def remove(n: Int)(p: (Any, OutputChannel[Any]) => Boolean): Option[(Any, OutputChannel[Any])] =
    removeInternal(p)(n) map (x => (x.msg, x.session))
*/
    
  /** Extracts the first message that satisfies the predicate <code>p</code>
   *  or <code>null</code> if <code>p</code> fails for all of them.
   */
  def extractFirst(p: (Any, OutputChannel[Any]) => Boolean): TransMQueueElement =
    removeInternal(p, Integer.MAX_VALUE)(0) orNull

  def extractFirst(tf: TranslucentFunction[Any, Any]): TransMQueueElement =
    removeInternal(tf)(0) orNull

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

  private def removeInternal(tf: TranslucentFunction[Any, Any])(n: Int): Option[TransMQueueElement] = {
    // iterate over classes for which function is defined
    val iter = tf.definedFor.iterator
    var bestQueue: TransMQueue = null
    var earliest = Integer.MAX_VALUE
    while (iter.hasNext) {
      val clazz = iter.next
//      println("looking up messages in queue for "+clazz)
      queueMap.get(clazz) match {
        case None =>
          /* do nothing */
        case Some(queue) =>
          // TODO: return also position in queue, then we can speed up removal
          val found = queue.findInternal((m: Any, o: OutputChannel[Any]) => tf.isDefinedAt(m), earliest)(0)
          if (!found.isEmpty) {
            bestQueue = queue
            earliest = found.get.time
          }
      }
    }
    if (bestQueue != null)
      bestQueue.removeInternal((m: Any, o: OutputChannel[Any]) => tf.isDefinedAt(m), earliest)(0)
    else
      None
  }

  private def removeInternal(p: (Any, OutputChannel[Any]) => Boolean, notLater: Int)(n: Int): Option[TransMQueueElement] = {
    var pos = 0

    def foundMsg(x: TransMQueueElement) = {        
      changeSize(-1)
      Some(x)
    }
    def test(msg: Any, session: OutputChannel[Any]): Boolean =
      p(msg, session) && (pos == n || { pos += 1 ; false })

    if (isEmpty)    // early return
      return None
    
    // special handling if returning the head
    if (first.time > notLater)
      None
    else if (test(first.msg, first.session)) {
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
        if (curr.time > notLater)
          return None
        else if (test(curr.msg, curr.session)) {
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

  private def findInternal(p: (Any, OutputChannel[Any]) => Boolean, notLater: Int)(n: Int): Option[TransMQueueElement] = {
    var pos = 0

    def foundMsg(x: TransMQueueElement) = {        
      Some(x)
    }
    def test(msg: Any, session: OutputChannel[Any]): Boolean =
      p(msg, session) && (pos == n || { pos += 1 ; false })

    if (isEmpty)    // early return
      return None
    
    // special handling if returning the head
    if (first.time > notLater)
      None
    else if (test(first.msg, first.session)) {
      foundMsg(first)
    }
    else {
      var curr = first.next   // init to element #2
      var prev = first
      
      while (curr != null) {
        if (curr.time > notLater)
          return None
        else if (test(curr.msg, curr.session)) {
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

