import scala.actors.Reactor

object ProducerConsumerCached extends Benchmark {
  case class Stop()
  case class Get(from: Reactor)
  case class Put(x: Int)

  class UnboundedBuffer extends Reactor {
    var consumer: Reactor = _
    val inner: TranslucentFunction[Any, Unit] = {
      case msg @ Put(x) =>
        consumer ! msg
        act()
    }
    val outer: TranslucentFunction[Any, Unit] = {
      case Stop() =>
      case Get(from) =>
        consumer = from
        react(inner)
    }
    def act() {
      react(outer)
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
    val step = n / 10
    var i = 0
    def act() {
      if (i < n) {
        i += 1
        if (delay > 0) Thread.sleep(delay)
        buf ! Get(this)
        react {
          case Put(res) =>
            if (i % step == 0)
              print("X")
            act()
        }
      } else {
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
