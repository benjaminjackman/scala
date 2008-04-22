package scala.swing

import event._
import Swing._

/**
 * @see javax.swing.JSplitPane
 */
class SplitPane(override val peer: javax.swing.JSplitPane) extends Component(peer) with Container with Orientable {
  def this(o: Orientation.Value) = this(new javax.swing.JSplitPane(o.id))

  leftComponent = new Component {}
  rightComponent = new Component {}
  
  def contents: Seq[Component] = List(leftComponent, rightComponent)
  def contents_=(left: Component, right: Component) {
    peer.setLeftComponent(left.peer)
    peer.setRightComponent(right.peer)
  }
  
  def topComponent: Component = Component.wrapperFor(peer.getTopComponent.asInstanceOf[javax.swing.JComponent])
  def topComponent_=(c: Component) { peer.setTopComponent(c.peer) }
  def bottomComponent: Component = Component.wrapperFor(peer.getBottomComponent.asInstanceOf[javax.swing.JComponent])
  def bottomComponent_=(c: Component) { peer.setBottomComponent(c.peer) }
  
  def leftComponent: Component = topComponent
  def leftComponent_=(c: Component) { topComponent = c }
  def rightComponent: Component = bottomComponent
  def rightComponent_=(c: Component) { bottomComponent = c }
  
  def dividerLocation: Int = peer.getDividerLocation
  def dividerLocation_=(n: Int) { peer.setDividerLocation(n) }
  def dividerSize: Int = peer.getDividerSize
  def dividerSize_=(n: Int) { peer.setDividerSize(n) }
  def resizeWeight: Double = peer.getResizeWeight
  def resizeWeight_=(n: Double) { peer.setResizeWeight(n) }
  
  def resetToPreferredSizes() { peer.resetToPreferredSizes() }
  
  def oneTouchExpandable: Boolean = peer.isOneTouchExpandable
  def oneTouchExpandable_=(b: Boolean) { peer.setOneTouchExpandable(b) }
  def continuousLayout: Boolean = peer.isContinuousLayout
  def continuousLayout_=(b: Boolean) { peer.setContinuousLayout(b) }
}