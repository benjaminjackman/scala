/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2010, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

// $Id$


package scala.collection
package mutable

/** The J2ME version of the library defined this trait with a <code>clone</code>
 *  method to substitute for the lack of <code>Object.clone</code> there.
 *
 *  @since 2.6
 */
@deprecated("use Cloneable instead")
trait CloneableCollection {
  override def clone(): AnyRef = super.clone()
}
