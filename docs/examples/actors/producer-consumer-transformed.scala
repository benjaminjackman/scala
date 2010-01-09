import scala.actors.{Reactor, Debug}

object ProducerConsumerTransformed {
  Debug.level = 3

  case class Stop()
  case class Get(from: Reactor)
  case class Put(x: Int)

  class UnboundedBuffer extends Reactor {
    def act() {
      react({
        val pf: PartialFunction[Any, Unit] = {
          case Stop() => exit()
          case Get(from) =>
            val consumer = from
            react({
              val pf: PartialFunction[Any, Unit] = {
                case msg @ Put(x) =>
                  consumer ! msg
                  act()
              }
              new TranslucentFunction(pf, List(classOf[Put]))
            })
        }
        new TranslucentFunction(pf, List(classOf[Stop], classOf[Get]))
      })
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
      println(this+" finished producing "+n+" items")
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
        react({
          val pf: PartialFunction[Any, Unit] = {
            case Put(res) =>
              if (i % step == 0)
                println(res)
              act()
          }
          new TranslucentFunction(pf, List(classOf[Put]))
        })
      } else {
        println(this+" finished consuming "+n+" items")
        parent ! Stop()
      }
    }
  }

  def main(args: Array[String]) {
    val num = if (args.length > 0)
      args(0).toInt
    else
      20000
    val parent = new Reactor {
      def act() {
        val buffer = new UnboundedBuffer
        buffer.start()
        val producer = new Producer(buffer, num, 0, this)
        producer.start()
        val consumer = new Consumer(buffer, num, 0, this)
        consumer.start()
        react({
          val pf: PartialFunction[Any, Unit] = {
            case Stop() =>
              println("parent: recv 1st stop")
              react({
                val pf: PartialFunction[Any, Unit] = {
                  case Stop() =>
                    println("parent: recv 2nd stop")
                    buffer ! Stop()
                }
                new TranslucentFunction(pf, List(classOf[Stop]))
              })
          }
          new TranslucentFunction(pf, List(classOf[Stop]))
        })
      }
    }
    parent.start()
  }
}
