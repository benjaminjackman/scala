import scala.actors.{Actor, Reactor}
import Actor._

object ProducerConsumer {
  case object Stop
  case class  Get(from: Reactor)
  case class  Put[T](x: T)

  class UnboundedBuffer extends Reactor {
    def act() {
      loop {
        react {
          case Stop => Actor.exit()
          case Get(from) =>
            val consumer = from
            react {
              case msg @ Put(x) =>
                consumer ! x
            }
        }
      }
    }
  }

  class Producer(buf: UnboundedBuffer, n: Int, delay: Long, parent: Actor) extends Reactor {
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

  class Consumer(buf: UnboundedBuffer, n: Int, delay: Long, parent: Actor) extends Reactor {
    def act() {
      val step = n / 10
      var i = 0
      loopWhile (i < n) {
        i += 1
        if (delay > 0) Thread.sleep(delay)
        buf ! Get(this)
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
      val producer = new Producer(buffer, 100000, 0, self)
      producer.start()
      val consumer = new Consumer(buffer, 100000, 0, self)
      consumer.start()
      react {
        case Stop => react { case Stop =>
          buffer ! Stop }
      }
    }
  }
}
