package scala

trait TranslucentFunction[-A, +B] extends PartialFunction[A, B] {

  def definedFor: Array[Class[_]]

}
