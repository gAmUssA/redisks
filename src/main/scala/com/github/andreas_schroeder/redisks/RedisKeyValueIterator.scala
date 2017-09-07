package com.github.andreas_schroeder.redisks

import java.util.NoSuchElementException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue}

import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.state.KeyValueIterator
import rx.lang.scala.Notification
import rx.lang.scala.Notification.OnNext

class RedisKeyValueIterator[K,V](bufferSize: Int) extends KeyValueIterator[K,V] {

  type Item = Notification[KeyValue[K,V]]
  val queue: BlockingQueue[Item] = new ArrayBlockingQueue[Item](bufferSize + 1)
  private val closedFlag = new AtomicBoolean()

  private var done: Boolean = false
  private var peeked: Boolean = false
  private var last: KeyValue[K, V] = _

  override def close(): Unit = this.synchronized {
    queue.clear()
    last = null
    closedFlag.set(true)
  }

  def closed: Boolean = closedFlag.get()

  override def peekNextKey: K = this.synchronized {
    if (peeked) {
      last.key
    } else {
      validateMaybeMoreAvailable()
      queue.take() match {
        case OnNext(kv) =>
          last = kv
          peeked = true
          last.key
        case _ =>
          done = true
          throw new NoSuchElementException()
      }
    }
  }

  private def validateMaybeMoreAvailable(): Unit = if (done && queue.isEmpty) throw new NoSuchElementException

  override def hasNext: Boolean = this.synchronized {
    if(done) {
      false
    } else if (peeked) {
      true
    } else {
      queue.take() match {
        case OnNext(kv) =>
          last = kv
          peeked = true
          true
        case _ =>
          done = true
          false
      }
    }
  }

  override def next: KeyValue[K, V] = this.synchronized {
    if (peeked) {
      peeked = false
      last
    } else {
      validateMaybeMoreAvailable()
      queue.take() match {
        case OnNext(kv) => kv
        case _ =>
          done = true
          throw new NoSuchElementException
      }
    }
  }
}
