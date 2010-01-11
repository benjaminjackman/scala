package scala

class TranslucentFunctionWrapper[-A, +B](pf: PartialFunction[A, B],
                                         val definedFor: Array[Class[_]]) extends TranslucentFunction[A, B] {
  def apply(x: A) =
    pf(x)
  def isDefinedAt(x: A) =
    pf.isDefinedAt(x)
}
