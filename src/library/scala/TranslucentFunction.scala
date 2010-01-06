package scala

class TranslucentFunction[T, +R](pf: PartialFunction[T, R], val definedFor: List[Class[S] forSome { type S }])
  extends PartialFunction[T, R] {
    def apply(x: T) =
      pf(x)
    def isDefinedAt(x: T) =
      pf.isDefinedAt(x)
  }
