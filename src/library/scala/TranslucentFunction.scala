package scala

trait TranslucentFunction[T, +R] extends PartialFunction[T, R] {
  def definedFor: List[Class[S] forSome { type S }]
}
