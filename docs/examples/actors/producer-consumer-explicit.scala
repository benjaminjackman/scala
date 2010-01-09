import scala.actors.{Actor, OutputChannel}
import Actor._
import scala.collection.mutable.Queue

object ProducerConsumerExplicit {
  case object Stop
  case object Get
  case class  Put(x: Int)

  class UnboundedBuffer extends Actor {
    val putQ = new Queue[Int]
    val getQ = new Queue[OutputChannel[Any]]

    def act() {
      loop {
        react {
          case Get =>
            if (putQ.isEmpty)
              getQ.enqueue(sender)
            else
              sender ! putQ.dequeue

          case Put(x) =>
            if (getQ.isEmpty)
              putQ.enqueue(x)
            else
              getQ.dequeue ! x

          case Stop => Actor.exit()
        }
      }
    }
  }

  class Producer(buf: UnboundedBuffer, n: Int, delay: Long, parent: Actor) extends Actor {
    def act() {
      var i = 0
      while (i < n) {
        i += 1
        if (delay > 0) Thread.sleep(delay)
        buf ! Put(42)
      }
      println(this+" finished producing "+n+" items")
      parent ! Stop
    }
  }

  class Consumer(buf: UnboundedBuffer, n: Int, delay: Long, parent: Actor) extends Actor {
    def act() {
      val step = n / 10
      var i = 0
      loopWhile (i < n) {
        i += 1
        if (delay > 0) Thread.sleep(delay)
        buf ! Get
        react {
          case res =>
            if (i % step == 0)
              println(res)
        }
      } andThen {
        println(this+" finished consuming "+n+" items")
        parent ! Stop
      }
    }
  }

  def main(args: Array[String]) {
    actor {
      val buffer = new UnboundedBuffer
      buffer.start()
      val producer = new Producer(buffer, 20000, 0, self)
      producer.start()
      val consumer = new Consumer(buffer, 20000, 0, self)
      consumer.start()
      react {
        case Stop => react { case Stop =>
          buffer ! Stop }
      }
    }
  }
}
