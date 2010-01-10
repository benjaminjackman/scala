import scala.actors.Reactor

trait Benchmark {
  def run(input: Int, whenFinished: Reactor): Unit
}

case class Finished()
