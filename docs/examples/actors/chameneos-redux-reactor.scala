package test

/**
 * The Computer Language Benchmarks Game
 * <p/>
 * URL: [http://shootout.alioth.debian.org/]
 * <p/>
 * Contributed by Julien Gaugaz.
 * <p/>
 * Inspired by the version contributed by Yura Taras and modified by Isaac Gouy.
 */
object ChameneosReactor {

  def benchAkkaActorsVsScalaActors = {

    def stressTestScalaActors(nrOfMessages: Int, nrOfActors: Int, sleepTime: Int): Long = {
      var totalTime = 0L

      import scala.actors.{Reactor, OutputChannel}
      import scala.actors.Actor._

      case class Finished(from: Reactor)

      abstract class Colour
      case object RED extends Colour
      case object YELLOW extends Colour
      case object BLUE extends Colour
      case object FADED extends Colour

      val colours = //Array(BLUE, RED, YELLOW)
        List(BLUE, RED, YELLOW)

      case class Meet(colour: Colour, from: Reactor)
      case class Change(colour: Colour)
      case class MeetingCount(count: Int)


      class Mall(var n: Int, numChameneos: Int) extends Reactor {
        var waitingChameneo: Option[OutputChannel[Any]] = None
        var startTime: Long = 0L

        start()

        def startChameneos(): Unit = {
          startTime = System.currentTimeMillis
          var i = 0
          while (i < numChameneos) {
            Chameneo(this, colours(i % 3), i).start()
            i = i + 1
          }
        }

        def act() {
          var sumMeetings = 0
          var numFaded = 0
          loop {
            react {

              case MeetingCount(i) => {
                numFaded = numFaded + 1
                sumMeetings = sumMeetings + i
                if (numFaded == numChameneos) {
                  totalTime = System.currentTimeMillis - startTime
                  exit()
                }
              }

              case msg@Meet(c, from) => {
                if (n > 0) {
                  waitingChameneo match {
                    case Some(chameneo) =>
                      n = n - 1
                      chameneo ! msg
                      waitingChameneo = None
                    case None =>
                      waitingChameneo = Some(from)
                  }
                } else {
                  waitingChameneo match {
                    case Some(chameneo) =>
                      chameneo ! Finished(this)
                    case None =>
                  }
                  from ! Finished(this)
                }
              }

            }
          }
        }
      }

      case class Chameneo(var mall: Mall, var colour: Colour, id: Int) extends Reactor {
        var meetings = 0

        def act() {
          loop {
            mall ! Meet(colour, this)
            react {
              case Meet(otherColour, from) =>
                colour = complement(otherColour)
                meetings = meetings + 1
                from ! Change(colour)
              case Change(newColour) =>
                colour = newColour
                meetings = meetings + 1
              case Finished(from) =>
                colour = FADED
                from ! MeetingCount(meetings)
                exit()
            }
          }
        }

        def complement(otherColour: Colour): Colour = {
          colour match {
            case RED => otherColour match {
              case RED => RED
              case YELLOW => BLUE
              case BLUE => YELLOW
              case FADED => FADED
            }
            case YELLOW => otherColour match {
              case RED => BLUE
              case YELLOW => YELLOW
              case BLUE => RED
              case FADED => FADED
            }
            case BLUE => otherColour match {
              case RED => YELLOW
              case YELLOW => RED
              case BLUE => BLUE
              case FADED => FADED
            }
            case FADED => FADED
          }
        }

        override def toString() = id + "(" + colour + ")"
      }

      val mall = new Mall(nrOfMessages, nrOfActors)
      mall.startChameneos
      Thread.sleep(sleepTime)
      totalTime
    }

    println("===========================================")
    println("== Benchmark Akka Actors vs Scala Actors ==")

    var nrOfMessages = 2000000
    var nrOfActors = 4
//    var akkaTime = stressTestAkkaActors(nrOfMessages, nrOfActors, 1000 * 20)
    var scalaTime = stressTestScalaActors(nrOfMessages, nrOfActors, 1000 * 80) //40
//    var ratio: Double = scalaTime.toDouble / akkaTime.toDouble

    println("\tNr of messages:\t" + nrOfMessages)
    println("\tNr of actors:\t" + nrOfActors)
//    println("\tAkka Actors:\t" + akkaTime + "\t milliseconds")
    println("\tScala Actors:\t" + scalaTime + "\t milliseconds")
//    println("\tAkka is " + ratio + " times faster\n")
    println("===========================================")
    assert(true)
  }

  def main(args: Array[String]) {
    benchAkkaActorsVsScalaActors    
  }
}
