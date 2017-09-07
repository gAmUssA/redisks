package com.github.andreas_schroeder.redisks

import java.io.IOException
import java.util
import java.util.{Comparator, Objects}

import com.lambdaworks.redis.{RedisClient, ScanArgs, ScriptOutputType, ValueScanCursor}
import com.lambdaworks.redis.api.StatefulRedisConnection
import com.lambdaworks.redis.api.rx.RedisReactiveCommands
import com.lambdaworks.redis.codec.ByteArrayCodec
import com.typesafe.scalalogging.StrictLogging
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.KeyValue
import org.apache.kafka.streams.processor.{ProcessorContext, StateStore}
import org.apache.kafka.streams.state.{KeyValueIterator, KeyValueStore}
import rx.lang.scala.JavaConversions.toScalaObservable
import rx.lang.scala.Observable
import rx.lang.scala.schedulers.{ComputationScheduler, IOScheduler}
import rx.lang.scala.subjects.PublishSubject
import rx.{Observable => JavaObservable}

import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.io.Source


class RedisKeyValueStore[K,V <: AnyRef](
                               val name: String,
                               redisClient: RedisClient,
                               keyPrefix: Array[Byte],
                               keyStoreKeyIn: Array[Byte],
                               keySerde: Serde[K],
                               valueSerde: Serde[V],
                               keyComparator: Comparator[K]
                             ) extends KeyValueStore[K,V] with StrictLogging {
  import RedisKeyValueStore._

  type Bytes = Array[Byte]

  private val keyStoreKeyTemplate: Bytes = new Array[Byte](keyStoreKeyIn.length + 4)
  System.arraycopy(keyStoreKeyIn, 0, keyStoreKeyTemplate, 0, keyStoreKeyIn.length)

  private val keyOrdering: Ordering[K] = Ordering.comparatorToOrdering(keyComparator)

  private val cancelableBackoff = new CancelableBackoff(
    1.second,
    60.seconds,
    100000,
    ComputationScheduler(),
    logBackoff,
    logBackoffFailure
   )

  private val scheduler = IOScheduler()

  private var open = false
  private var context: ProcessorContext = _
  private var redis: RedisReactiveCommands[Bytes, Bytes] = _

  private var codec: RedisToKafkaCodec[K, V] = _
  private var putIfAbsentScript: String = _
  private var putScript: String = _
  private var deleteScript: String = _

  private val nullValue: V = null.asInstanceOf[V]

  override def init(context: ProcessorContext, root: StateStore): Unit = {
    val codec: RedisToKafkaCodec[K, V] = RedisToKafkaCodec.fromSerdes(if (keySerde == null) context.keySerde.asInstanceOf[Serde[K]]
    else keySerde, if (valueSerde == null) context.valueSerde.asInstanceOf[Serde[V]]
    else valueSerde, name)
    val connection: StatefulRedisConnection[Bytes, Bytes] = redisClient.connect(ByteArrayCodec.INSTANCE)
    val reactive: RedisReactiveCommands[Bytes, Bytes] = connection.reactive
    if (root != null) context.register(root, false, (_, _) => ())

    this.synchronized {
      this.context = context
      this.codec = codec
      this.redis = reactive
    }

    val putIfAbsentScript: String = scriptLoad(PUT_IF_ABSENT_SCRIPT)
    val deleteScript: String = scriptLoad(DELETE_SCRIPT)
    val putScript: String = scriptLoad(PUT_SCRIPT)

    this.synchronized {
      this.putScript = putScript
      this.putIfAbsentScript = putIfAbsentScript
      this.deleteScript = deleteScript
    }

    open = true
  }

  private def scriptLoad(content: String): String = cmd(_.scriptLoad(content.getBytes)).toBlocking.first

  private def keystoreKey: Bytes = keystoreKeyWithPartition(context.partition())

  private def keystoreKeyWithPartition(partition: Int): Bytes = {
    KeyUtils.addPartition(partition, keyStoreKeyTemplate, keyStoreKeyTemplate.length - 4)
    keyStoreKeyTemplate
  }

  override def flush(): Unit = {
    redis.getStatefulConnection.flushCommands()
  }

  override def close(): Unit = {
    redis.close()
    open = false
  }

  override def persistent: Boolean = true

  override def isOpen: Boolean = open

  private def prefixedRawKey(key: K): Bytes = codec.encodeKey(key, context.partition, keyPrefix)._2

  private def prefixedRawKeys(key: K): (Bytes, Bytes) = codec.encodeKey(key, context.partition, keyPrefix)

  private def rawValue(value: V): Bytes = {
    if (value == null) {
      Array.empty[Byte]
    } else {
      codec.encodeValue(value)
    }
  }

  private def value(rawValue: Bytes): V = {
    if (rawValue == null) {
      nullValue
    } else {
      codec.decodeValue(rawValue)
    }
  }

  private def key(rawKey: Bytes): K = codec.decodeKey(rawKey)

  private def cmd[T](f: RedisReactiveCommands[Bytes, Bytes] => JavaObservable[T]): Observable[T] =
    toScalaObservable(f(redis))

  private def logBackoff(ex: Throwable, tryNumber: Int): Unit =
    logger.warn("Attempt {} failed with {}: {}", tryNumber, ex.getClass.getSimpleName, ex.getMessage)

  def logBackoffFailure(ex: Throwable): Unit =
    logger.warn("Retry with backoff failed, finally giving up. {}: {}", ex.getClass.getSimpleName, ex.getMessage)

  def backoff(attempts: Observable[Throwable]): Observable[Any] = cancelableBackoff.backoff(attempts)

  def backoffOrCancelWhen(cancel: => Boolean)(attempts: Observable[Throwable]): Observable[Any] =
    cancelableBackoff.backoffOrCancelWhen(cancel, attempts)


  override def put(key: K, value: V): Unit = this.synchronized {
    Objects.requireNonNull(key, "key cannot be null")
    Objects.requireNonNull(value, "value cannot be null")
    val (vanillaKey, prefixedRawKey) = prefixedRawKeys(key)
    cmd(_.evalsha(
        putScript,
        ScriptOutputType.STATUS,
        Array(prefixedRawKey, keystoreKey),
        rawValue(value), vanillaKey))
      .observeOn(scheduler)
      .subscribeOn(scheduler)
      .retryWhen(backoff)
      .subscribe()
  }


  override def putIfAbsent(key: K, value: V): V = this.synchronized {
    Objects.requireNonNull(key, "key cannot be null")
    Objects.requireNonNull(value, "value cannot be null")
    val (vanillaKey, prefixedRawKey) = prefixedRawKeys(key)
    cmd(_.evalsha[Bytes](
        putIfAbsentScript,
        ScriptOutputType.VALUE,
        Array(prefixedRawKey, keystoreKey),
        rawValue(value),
        vanillaKey))
      .retryWhen(backoff)
      .map(this.value)
      .toBlocking
      .headOrElse(nullValue)
  }

  override def putAll(entries: util.List[KeyValue[K, V]]): Unit = this.synchronized {
    val map: util.Map[Bytes, Bytes] = new util.HashMap(entries.size)
    val keys = new ListBuffer[Bytes]
    for (entry <- entries.asScala) {
      val (vanillaKey, prefixedRawKey) = prefixedRawKeys(entry.key)
      map.put(prefixedRawKey, rawValue(entry.value))
      keys += vanillaKey
    }
    cmd(_.mset(map))
      .observeOn(scheduler)
      .subscribeOn(scheduler)
      .retryWhen(backoff)
      .flatMap(_ => cmd(_.sadd(keystoreKey, keys:_*)).retryWhen(backoff))
      .subscribe()
  }

  override def delete(key: K): V = this.synchronized {
    Objects.requireNonNull(key, "key cannot be null")
    val (vanillaKey, prefixedRawKey) = prefixedRawKeys(key)
    cmd(_.evalsha[Bytes](
        deleteScript,
        ScriptOutputType.VALUE,
        Array(prefixedRawKey, keystoreKey),
        vanillaKey))
      .retryWhen(backoff)
      .map(this.value)
      .toBlocking
      .headOrElse(nullValue)
  }

  override def get(key: K): V = this.synchronized {
    Objects.requireNonNull(key, "key cannot be null")
    cmd(_.get(prefixedRawKey(key)))
      .retryWhen(backoff)
      .map(this.value)
      .toBlocking
      .headOrElse(nullValue)
  }

  override def range(from: K, to: K): KeyValueIterator[K, V] = this.synchronized {
    import keyOrdering._
    all((k: K) => from <= k && k <= to)
  }

  override def all: KeyValueIterator[K, V] = this.synchronized { all((k: K) => true) }

  private def all(predicate: K => Boolean): KeyValueIterator[K, V] = {
    val batchSize: Int = 50
    val partition = context.taskId().partition
    val it: RedisKeyValueIterator[K, V] = new RedisKeyValueIterator[K, V](batchSize)

    val partitionKeystoreKey = keystoreKeyWithPartition(partition).clone()

    def collectKeys(rawKeys: Seq[Bytes]) = {
      val prefixedKeys: mutable.Buffer[Bytes] = new mutable.ArrayBuffer(rawKeys.length)
      val keys: mutable.Buffer[K] = new mutable.ArrayBuffer(rawKeys.length)
      for {
        rawKey <- rawKeys
        parsedKey = key(rawKey)
        if predicate(parsedKey)
      } {
        prefixedKeys += KeyUtils.prefixKey(rawKey, partition, keyPrefix)
        keys += parsedKey
      }
      (prefixedKeys, keys)
    }

    val backoffOrCancel: Observable[Throwable] => Observable[Any] = backoffOrCancelWhen(it.closed)

    val cursorSubject = PublishSubject[Option[ValueScanCursor[Bytes]]]()
    val scanArgs: ScanArgs = ScanArgs.Builder.limit(batchSize)

    def nextKeyBatch(maybeCursor: Option[ValueScanCursor[Bytes]]): Observable[ValueScanCursor[Bytes]] =
      maybeCursor match {
        case None => cmd(_.sscan(partitionKeystoreKey, scanArgs))
        case Some(lastCursor) => cmd(_.sscan(partitionKeystoreKey, lastCursor, scanArgs))
      }

    def collectKeyValues(cursor: ValueScanCursor[Bytes]): Observable[KeyValue[K, V]] = {
      val rawKeys = cursor.getValues.asScala
      val (prefixedKeys, keys) = collectKeys(rawKeys)
      if (keys.isEmpty) {
        cursorSubject.onCompleted()
        Observable.empty
      } else {
        cmd(_.mget(prefixedKeys: _*))
          .retryWhen(backoffOrCancel)
          .zipWith(keys)((rawValue, key) => new KeyValue(key, value(rawValue)))
          .filter(_ => !it.closed)
          .doOnCompleted(cursorSubject.onNext(Some(cursor)))
      }
    }

    val scheduler = ComputationScheduler()
    cursorSubject
      .observeOn(scheduler)
      .flatMap {
        case Some(cursor) if cursor.isFinished || it.closed =>
          cursorSubject.onCompleted()
          Observable.empty
        case maybeLastCursor =>
          nextKeyBatch(maybeLastCursor)
            .observeOn(scheduler)
            .retryWhen(backoffOrCancel)
            .flatMap(collectKeyValues)
      }
      .materialize
      .foreach(it.queue.put)

    cursorSubject.onNext(None)
    it
  }

  override def approximateNumEntries: Long = this.synchronized {
    cmd(_.scard(keystoreKeyWithPartition(context.taskId().partition)))
      .toBlocking
      .first
  }
}

object RedisKeyValueStore extends StrictLogging {

  private def loadScript(name: String): String =
    try {
      Source.fromResource(name).mkString
    } catch {
      case ex: IOException => throw new RuntimeException("Failed to load lua script '" + name + "'", ex)
    }

  private val PUT_IF_ABSENT_SCRIPT = loadScript("put_if_absent.lua")
  private val PUT_SCRIPT = loadScript("put.lua")
  private val DELETE_SCRIPT = loadScript("delete.lua")

}
