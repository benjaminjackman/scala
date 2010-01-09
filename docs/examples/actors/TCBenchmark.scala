import scala.actors.Reactor
import scala.actors.Actor._

trait Benchmark {
  def run(input: Int, whenFinished: Reactor): Unit
}

case class Finished()

object TCBenchmark {

  def invokeBenchmark(which: String, input: Int, parent: Reactor) = {
    val benchmark = which match {
      case "producer-consumer" => ProducerConsumer
      case "producer-consumer-explicit-compose" => ProducerConsumerExplicitCompose
    }
    benchmark.run(input, parent)
  }

  def main(args: Array[String]) {
    // benchmark from to step
    val reporter = new Reactor {
      val benchmark = args(0).toString
      val inputFrom = args(1).toInt
      val inputTo =   args(2).toInt
      val step =      args(3).toInt
      var medians =   List[Long]()
      def act() {
        var input = inputFrom
        loopWhile (input <= inputTo) {
          input += step
        
          var results =   List[Long]()
          var i = 6
          loopWhile (i > 0) {
            i -= 1
            val startTime = System.nanoTime()
            invokeBenchmark(benchmark, input, this)
            react {
              case Finished() =>
                if (i < 5) {
                  val estimatedTime = System.nanoTime() - startTime
                  results = ((estimatedTime / 1000) / 1000) :: results
                }
            }
          } andThen {
            val sorted = results.sort((x, y) => x < y)
            println("run results: "+results.mkString(", ")+" - median: "+sorted(2))
            medians = medians ::: List(sorted(2))
          }
        } andThen {
          println("final results: "+medians.mkString(" "))
        }
      }
    }
    reporter.start()
  }

}
