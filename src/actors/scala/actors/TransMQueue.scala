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

private[actors] class TransMQueue(protected val label: String, var count: Int) {
  protected var first: TransMQueueElement = null
  protected var last: TransMQueueElement = null  // last eq null iff list is empty
  private var _size = 0
  
  protected val queueMap = new HashMap[Class[T] forSome { type T }, TransMQueue]

  def nextTime = { count += 1; count }

  def this(initCount: Int) = this("", initCount)
  def this(name: String) = this(name, 0)
  def this() = this("", 0)

  def size = _size
  final def isEmpty = last eq null

  protected def changeSize(diff: Int) {
    _size += diff
  }

  var enableTranslucent = true

  def appendTagged(msg: Any, session: OutputChannel[Any]) {
    val queue = if (enableTranslucent) {
      val msgClass = msg.asInstanceOf[AnyRef].getClass
//    println("appending (class of "+msg+" is "+msgClass+")")
    /* if there is no queue for msgClass in queueMap, then
       either msgClass is not a case class or it has not yet been
       tried to received. In this case, append to global queue (this). */
      queueMap.getOrElse(msgClass, this)
    } else if (size > 1000) {
      enableTranslucent = true
      this
    } else
      this
    queue.append(msg, session, nextTime)
  }

  def append(other: TransMQueue) {
    if (!other.isEmpty) {
      if (isEmpty)
        first = other.first
      else
        last.next = other.first
      last = other.last
      changeSize(other.size)
    }
  }

  // assume that all queue classes of `other` also exist in `this`
  def appendTagged(other: TransMQueue) {
    try {
      // append global queues
      this.append(other)
      this.count = other.count

      // append queues of `other` to queues of `this`
      val keyIter = other.queueMap.keysIterator
      while (keyIter.hasNext) {
        val key = keyIter.next
        val queue = other.queueMap(key)
        queueMap.get(key) match {
          case None =>
            println("BIG PROBLEM")
            queueMap += (key -> queue)
          case Some(subqueue) => subqueue.append(queue)
        }
      }
    } catch {
      case e: Throwable => e.printStackTrace()
    }
  }

  def printStats() {
    println(queueMap.toString)
    val keyIter = queueMap.keysIterator
    while (keyIter.hasNext) {
      val key = keyIter.next
      val box = queueMap(key)
      println(key+":"+box.size)
    }
  }

  def append(msg: Any, session: OutputChannel[Any]) {
    changeSize(1) // size always increases by 1
    // overhead compared to non-translucent version: compute `nextTime`.
    val el = new TransMQueueElement(msg, session, nextTime)
    if (isEmpty) first = el
    else last.next = el
    last = el
  }

  def append(msg: Any, session: OutputChannel[Any], time: Int) {
    changeSize(1) // size always increases by 1
    val el = new TransMQueueElement(msg, session, time)
    if (isEmpty) first = el
    else last.next = el
    last = el
  }

  def append(elem: TransMQueueElement) {
    changeSize(1) // size always increases by 1
    if (isEmpty) first = elem
    else last.next = elem
    last = elem
  }

  def foreach(f: (Any, OutputChannel[Any]) => Unit) {
    var curr = first
    while (curr != null) {
      f(curr.msg, curr.session)
      curr = curr.next
    }
  }

  /* Traverses this queue moving each element whose msg field has class `clss`
   * to queue `target`. Uses efficient method to append directly `TransMQueueElement`s.
   */
  def moveClassTo(clss: Class[_], target: TransMQueue) {
    // special case first element
    while (first != null && first.msg.asInstanceOf[AnyRef].getClass == clss) {
      val next = first.next
      first.next = null
      target append first
      first = next
    }

    if (first != null) {
      var prev = first
      var curr = first.next
      while (curr != null) {
        if (curr.msg.asInstanceOf[AnyRef].getClass == clss) {
          val next = curr.next
          curr.next = null
          target append curr
          prev.next = next
        }
        prev = curr
        curr = curr.next
      }
    }
  }

  def foreachTagged(f: (Any, OutputChannel[Any]) => Unit) {
    val keyIter = queueMap.keysIterator
    while (keyIter.hasNext) {
      val key = keyIter.next
      val box = queueMap(key)
      box.foreach(f)
    }
    this.foreach(f)
  }

  def foreachAppendTo(mbox: TransMQueue) {
    var curr = first
    while (curr != null) {
      mbox.appendTagged(curr.msg, curr.session)
      curr = curr.next
    }
  }

  def foreachAppendToTagged(mbox: TransMQueue) {
    val keyIter = queueMap.keysIterator
    while (keyIter.hasNext) {
      val key = keyIter.next
      val box = queueMap(key)
      box.foreachAppendTo(mbox)
    }
    this.foreachAppendTo(mbox)
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

  def extractFirst(tf: TranslucentFunction[Any, Any]): TransMQueueElement = {
    var bestQueue: TransMQueue = null
    var earliest = Integer.MAX_VALUE
    val defFor = tf.definedFor

    if (enableTranslucent) {
    if (defFor.isInstanceOf[Class[_]]) {
      val clazz = defFor.asInstanceOf[Class[_]]
      val queue = queueMap.getOrElse(clazz, {
        val newQueue = new TransMQueue
        moveClassTo(clazz, newQueue) // traverses global queue (this), moves messages
        queueMap += (clazz -> newQueue)
        newQueue
      })
      //TODO: make findInternal return Nullable instead of Option
      //TODO: return also position in queue, then we can speed up removal
      val found = queue.findInternal(tf, earliest)
      if (!found.isEmpty) {
        bestQueue = queue
        earliest = found.get.time
      }
    } else {
      val defForList = defFor.asInstanceOf[List[Class[_]]]
      if (defForList.isEmpty) { //TODO: replace with faster instanceof test?
        // (a) search through global queue (this)
        val found = this.findInternal(tf, earliest)
        if (!found.isEmpty) {
          bestQueue = this
          earliest = found.get.time
        }
        // (b) search through all other queues
        queueMap.values.foreach { queue =>
          val found = queue.findInternal(tf, earliest)
                                 if (!found.isEmpty) {
                                   bestQueue = queue
                                   earliest = found.get.time
                                 }
                               }
      } else defForList.foreach(clazz => {
        // Step 1: make sure for all classes in tf.definedFor exist separate queues
        // if necessary move messages from global queue (this) to new separate queues
        val queue = queueMap.getOrElse(clazz, {
          val newQueue = new TransMQueue
          moveClassTo(clazz, newQueue) // traverses global queue (this), moves messages
          queueMap += (clazz -> newQueue)
          newQueue
        })
        //TODO: make findInternal return Nullable instead of Option
        //TODO: return also position in queue, then we can speed up removal
        val found = queue.findInternal(tf, earliest)
        if (!found.isEmpty) {
          bestQueue = queue
          earliest = found.get.time
        }
      })
    }

    } else {
      val found = this.findInternal(tf, earliest)
      if (!found.isEmpty) {
        bestQueue = this
        earliest = found.get.time
      }
    }

    if (bestQueue != null)
      bestQueue.removeInternal(tf, earliest)
    else
      null
  }

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

  private def removeInternal(pf: PartialFunction[Any, Any], notLater: Int): TransMQueueElement = {
    def foundMsg(x: TransMQueueElement) = {        
      changeSize(-1)
      x
    }

    if (isEmpty)    // early return
      return null
    
    // special handling if returning the head
    if (first.time > notLater)
      null
    else if (pf.isDefinedAt(first.msg)) {
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
          return null
        else if (pf.isDefinedAt(curr.msg)) {
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
      null
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

  private def findInternal(pf: PartialFunction[Any, Any], notLater: Int): Option[TransMQueueElement] = {
    if (isEmpty)    // early return
      return None
    
    // special handling if returning the head
    if (first.time > notLater)
      None
    else if (pf.isDefinedAt(first.msg)) {
      Some(first)
    }
    else {
      var curr = first.next   // init to element #2
      var prev = first
      
      while (curr != null) {
        if (curr.time > notLater)
          return None
        else if (pf.isDefinedAt(curr.msg)) {
          return Some(curr) // early return
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

