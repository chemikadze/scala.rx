import java.util.concurrent.atomic.AtomicInteger

import collection.mutable
import scala.util.Try

package object rx {
  val contextStack = new ThreadLocal[mutable.Buffer[Rx[_]]]{
    override def initialValue = mutable.Buffer.empty[Rx[_]]
  }
  def contextHead = contextStack.get().lastOption
  val idCounter = new AtomicInteger
  trait Node[T]{
    def value: T
    val downStream = mutable.Set.empty[Rx[_]]
    val observers = mutable.Set.empty[Obs]
    def apply() = {
      for(c <- contextHead){
        downStream.add(c)
        c.depth = c.depth max (depth + 1)
      }
      value
    }
    def depth: Int

    def trigger(thunk: => Unit) = {
      thunk
      observers.add(new Obs(() => thunk))
    }
    def triggerLater(thunk: => Unit) = {
      observers.add(new Obs(() => thunk))
    }
  }
  object VarTuple{
    implicit def tuple2VarTuple[T](t: (Var[T], T)) = {
      VarTuple(t._1, t._2)
    }
    implicit def tuples2VarTuple[T](ts: Seq[(Var[T], T)]) = {
      ts.map(t => VarTuple(t._1, t._2))
    }
  }
  case class VarTuple[T](v: Var[T], value: T){
    def set() = v.value = value
  }
  object Var{
    def set(args: VarTuple[_]*) = {
      args.foreach(_.set())
      doRecalc(
        args.flatMap(_.v.downStream),
        args.flatMap(_.v.observers)
      )
    }
  }
  case class Var[T](var value: T) extends Node[T]{

    def depth = 0
    def update(newValue: T): Unit = {
      val toPing = downStream.toSet
      downStream.clear()
      value = newValue
      doRecalc(toPing, observers)
    }
    def calcSet(): Seq[Rx[_]] = downStream.toSeq
  }

  object Rx{
    def apply[T](func: => T) = new Rx(() => func)
  }

  case class Rx[T](func: () => T, id: Int = idCounter.getAndIncrement) extends Node[T] {
    var depth = 0
    val upStream = mutable.Set.empty[Node[_]]
    val owned = mutable.Set.empty[Node[_]]
    def calc(): Try[T] = {
      downStream.clear()
      owned.clear()
      contextStack.get().append(this)
      val r = Try(func())
      contextStack.get().trimEnd(1)
      r
    }
    def value = cached.get
    def toTry = cached
    var cached: Try[T] = {
      contextHead.foreach(_.owned.add(this))
      calc()
    }

    def kill() = {
      upStream.foreach(_.downStream.remove(this))
      upStream.clear()
    }

    def update(): Unit = {
      cached = calc()
    }

    def recalc() = {
      update()
      doRecalc(this.downStream, observers)
    }
  }

  def doRecalc(rxs: Iterable[Rx[_]], obs: Iterable[Obs]): Unit = {
    implicit val ordering = Ordering.by[Rx[_], Int](-_.depth)
    val queue = rxs.to[mutable.PriorityQueue]
    val seen = mutable.Set.empty[Int]
    val observers = obs.to[mutable.Set]
    while(queue.size > 0){
      val min = queue.dequeue()
      if (!seen(min.id)) {
        queue ++= min.downStream
        observers ++= min.observers
        min.update()
        seen.add(min.id)
      }
    }
    observers.foreach(_.thunk())
  }

  case class Obs(thunk: () => Unit)
}

