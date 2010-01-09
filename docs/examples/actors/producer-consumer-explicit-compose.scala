import scala.actors.{Actor, Reactor, OutputChannel}
import Actor._
import scala.collection.mutable.Queue

object ProducerConsumerExplicitCompose extends Benchmark {
  case class Stop()
  case class Get(from: Reactor)
  case class Put(x: Int)

  class UnboundedBuffer extends Reactor {
    val putQ = new Queue[Int]
    val getQ = new Queue[OutputChannel[Any]]

    def act() {
      loop { react {
        case Stop() => this.exit()

        case Get(from) =>
          if (putQ.isEmpty)
            getQ.enqueue(from)
          else
            from ! Put(putQ.dequeue)

        case Put(x) =>
          if (getQ.isEmpty)
            putQ.enqueue(x)
          else
            getQ.dequeue ! Put(x)
      } }
    }
  }

  class Producer(buf: UnboundedBuffer, n: Int, delay: Long, parent: Reactor) extends Reactor {
    def act() {
      var i = 0
      while (i < n) {
        i += 1
        if (delay > 0) Thread.sleep(delay)
        buf ! Put(42)
      }
      parent ! Stop()
    }
  }

  class Consumer(buf: UnboundedBuffer, n: Int, delay: Long, parent: Reactor) extends Reactor {
    def act() {
      val step = n / 10
      var i = 0
      loopWhile (i < n) {
        i += 1
        if (delay > 0) Thread.sleep(delay)
        buf ! Get(this)
        react {
          case Put(res) =>
            if (i % step == 0)
              print("X")
        }
      } andThen {
        println(".")
        parent ! Stop()
      }
    }
  }

  def run(input: Int, reportTo: Reactor) {
    val parent = new Reactor {
      def act() {
        val buffer = new UnboundedBuffer
        buffer.start()
        val producer = new Producer(buffer, input, 0, this)
        producer.start()
        val consumer = new Consumer(buffer, input, 0, this)
        consumer.start()
        react {
          case Stop() =>
            react {
              case Stop() =>
                buffer ! Stop()
                reportTo ! Finished()
            }
        }
      }
    }
    parent.start()
  }

  def main(args: Array[String]) {
    run(20000, { val r = new Reactor { def act() {} }; r })
  }
}
