/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003, LAMP/EPFL                  **
**  __\ \/ /__/ __ |/ /__/ __ |                                         **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
** $Id$
\*                                                                      */

package scala.collection.mutable;

/** This class is typically used as a mixin. It turns maps which map <code>A</code>
 *  to <code>Set[B]</code> objects into multi maps which map <code>A</code> to
 *  <code>B</code> objects.
 *
 *  @author  Matthias Zenger
 *  @version 1.0, 08/07/2003
 */
trait MultiMap[A, B] extends scala.collection.mutable.Map[A, scala.collection.mutable.Set[B]] {
    protected def makeSet: scala.collection.mutable.Set[B] = new HashSet[B];
    
    def add(key: A, value: B): Unit = get(key) match {
        case None => val set = makeSet;
                     set.add(value);
                     update(key, set);
        case Some(set) => set.add(value);
    }
    
    override def remove(key: A) = super.remove(key);
    
    override def remove(key: A, value: B) = get(key) match {
        case None => 
        case Some(set) => set.remove(value);
    }
    
    def exists(key: A, p: B => Boolean): Boolean = get(key) match {
        case None => false
        case Some(set) => set.exists(p);
    }
}
