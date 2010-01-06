import scala.actors.{Actor, Reactor, Debug}
import Actor._

object ProducerConsumerTransformed {
  Debug.level = 3

  case class  Stop()
  case class  Get(from: Reactor)
  case class  Put(x: Int)

  class UnboundedBuffer extends Reactor {
    def act() {
      var numPutSent = 0
      Thread.sleep(1000)
      mailbox.printStats()
      loop {
        react({
          val pf: PartialFunction[Any, Unit] = {
            case Stop() => this.exit()
            case Get(from) =>
//              println("buf: recv Get")
              val consumer = from
              react({
                val pf: PartialFunction[Any, Unit] = {
                  case msg @ Put(x) =>
//                    println("buf: recv Put")
                    consumer ! msg
                    numPutSent += 1
                    if (numPutSent % 1000 == 0)
                      println("buf: sent "+numPutSent+" Puts")
                }
                new TranslucentFunction(pf, List(classOf[Put]))
              })
          }
          new TranslucentFunction(pf, List(classOf[Stop], classOf[Get]))
        })
      }
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
//      println("prod: finished")
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
        react({
          val pf: PartialFunction[Any, Unit] = {
            case Put(res) =>
//              println("cons: recv Put")
              if (i % step == 0)
                println(res)
          }
          new TranslucentFunction[Any, Unit](pf, List(classOf[Put]))
        })
      } andThen {
        println(this+" finished consuming "+n+" items")
        parent ! Stop()
//        println("cons: finished")
      }
    }
  }

  def main(args: Array[String]) {
    val parent = new Reactor {
      def act() {
        val buffer = new UnboundedBuffer
        buffer.start()
        val producer = new Producer(buffer, 100000, 0, this)
        producer.start()
        val consumer = new Consumer(buffer, 100000, 0, this)
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
          new TranslucentFunction[Any, Unit](pf, List(classOf[Stop]))
        })
      }
    }
    parent.start()
  }
}
