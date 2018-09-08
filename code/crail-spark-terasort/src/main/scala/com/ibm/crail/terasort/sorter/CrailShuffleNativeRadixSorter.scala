/*
 * Crail-terasort: An example terasort program for Sprak and crail
 *
 * Author: Jonas Pfefferle <jpf@zurich.ibm.com>
 *
 * Copyright (C) 2016, IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.ibm.crail.terasort.sorter

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

import com.ibm.crail.terasort.{TeraInputFormat, TeraSort}
import com.ibm.radixsort.NativeRadixSort
import org.apache.spark.TaskContext
import org.apache.spark.serializer.{CrailDeserializationStream, Serializer}
import org.apache.spark.shuffle.CrailShuffleSorter
import sun.nio.ch.DirectBuffer

import scala.collection.mutable.ListBuffer

private case class OrderedByteBuffer(buf: ByteBuffer) extends Ordered[OrderedByteBuffer] {
  override def compare(that: OrderedByteBuffer): Int = {
    /* read int from the current position */
    val thisInt = buf.getInt
    /* revert */
    buf.position(buf.position() - Integer.BYTES)
    /* read int from the current position */
    val thatInt = that.buf.getInt
    /* revert */
    that.buf.position(that.buf.position() - Integer.BYTES)
    /* compare and return results */
    thisInt - thatInt
  }

  /* just for debugging */
  override def toString : String = {
    buf.toString
  }
}

private class OrderedByteBufferCache(size: Int) {

 private val cacheBufferList = ListBuffer[OrderedByteBuffer]()
  private val get = new AtomicLong(0)
  private val put = new AtomicLong(0)
  private val miss = new AtomicLong(0)

  def getBuffer : OrderedByteBuffer = {
    get.incrementAndGet()
    this.synchronized {
      if (cacheBufferList.nonEmpty) {
        val ret = cacheBufferList.head
        cacheBufferList.trimStart(1)
        return ret
      }
    }
    miss.incrementAndGet()
    /* otherwise we are here then ...allocate new */
    OrderedByteBuffer(ByteBuffer.allocateDirect(size))
  }

  def putBuffer(buf: OrderedByteBuffer) : Unit = {
    put.incrementAndGet()
    this.synchronized {
      buf.buf.clear()
      cacheBufferList += buf
    }
  }

  def getStatistics:String = {
    TeraSort.verbosePrefixCache + " TID: " + TaskContext.get().taskAttemptId() + " OrderedCache: totalAccesses " +
      get.get() + " misses " + miss.get() + " puts " + put.get() +
      " hitrate " + ((get.get() - miss.get) * 100 / get.get()) + " %"
  }
}

private object OrderedByteBufferCache {
  private var instance:OrderedByteBufferCache = null

  final def getInstance():OrderedByteBufferCache = {
    this.synchronized {
      if(instance == null) {
        val size = TaskContext.get().getLocalProperty(TeraSort.f22BufSizeKey).toInt
        instance = new OrderedByteBufferCache(size)
      }
    }
    instance
  }
}

class CrailShuffleNativeRadixSorter extends CrailShuffleSorter {
  override def sort[K, C](context: TaskContext, keyOrd: Ordering[K], ser: Serializer,
                          inputStream: CrailDeserializationStream): Iterator[Product2[K, C]] = {

    val verbose = TaskContext.get().getLocalProperty(TeraSort.verboseKey).toBoolean
    /* we collect data in a list of OrderedByteBuffer */
    val bufferList = ListBuffer[OrderedByteBuffer]()
    var totalBytesRead = 0
    /* expectedRead needs to be a multiple of KV size otherwise we will break the record boundary */
    val expectedRead = TaskContext.get().getLocalProperty(TeraSort.f22BufSizeKey).toInt
    var hitEOF = false // did we hit EOF?
    while(!hitEOF) {
      // we get a new buffer with an anticipated size of `expectedRead` bytes
      val oBuf = OrderedByteBufferCache.getInstance().getBuffer
      //we need to fill this buffer - the F22 read method loops over the input stream, no need to do it here
      var ret = inputStream.read(oBuf.buf)
      if(ret == -1) {
        hitEOF = true
      }
      // after the reading, we flip
      oBuf.buf.flip()
      // this gives us how much we have read
      val bytesRead = oBuf.buf.remaining()
      if(bytesRead > 0) {
        // if we read more than zero, we do some work
        require(bytesRead % TeraInputFormat.RECORD_LEN == 0,
          " bytesRead " + bytesRead + " is not a multiple of the record length " + TeraInputFormat.RECORD_LEN)
        // add the buffer to the list
        bufferList+=oBuf
        /* once we have it then lets sort it */
        NativeRadixSort.sort(oBuf.buf.asInstanceOf[DirectBuffer].address() /* address */,
          bytesRead/TeraInputFormat.RECORD_LEN /* number of elements */,
          TeraInputFormat.KEY_LEN /* can use on the serializer interface of keySize and valueSize */,
          TeraInputFormat.RECORD_LEN)
      }
      // general accounting for the total read size
      totalBytesRead+=bytesRead
    }
    if(verbose) {
      System.err.println(TeraSort.verbosePrefixSorter + " TID: " + TaskContext.get().taskAttemptId() +
        " assembled " + totalBytesRead + " bytes in " + bufferList.length + " buffers")
    }

    require(totalBytesRead % TeraInputFormat.RECORD_LEN == 0 ,
      " totalBytesRead " + totalBytesRead + " is not a multiple of the record length " + TeraInputFormat.RECORD_LEN)
    new ByteBufferIterator(bufferList, totalBytesRead, verbose).asInstanceOf[Iterator[Product2[K, C]]]
  }
}

private class ByteBufferIterator(bufferList: ListBuffer[OrderedByteBuffer], totalBytesRead: Int, verbose: Boolean)
  extends Iterator[Product2[Array[Byte], Array[Byte]]] {

  private val key = new Array[Byte](TeraInputFormat.KEY_LEN)
  private val value = new Array[Byte](TeraInputFormat.VALUE_LEN)
  private val numElements = totalBytesRead/TeraInputFormat.RECORD_LEN
  private var processed = 0
  private val kv = (key, value)
  private var currentMin = bufferList.head
  private var bufferReturned = false

  override def hasNext: Boolean = {
    val more = processed < numElements
    if(!more && !bufferReturned){
      val ins = OrderedByteBufferCache.getInstance()
      /* hit the end */
      bufferList.foreach(p => ins.putBuffer(p))
      bufferReturned = true
      if(verbose) {
        System.err.println(ins.getStatistics)
      }
    }
    more
  }

  def calcNextMinBuffer(): Unit = {
    // we read from buffer then that means we have to reset it too
    bufferList.foreach(p => {
      if(p.buf.remaining() > 0 && (currentMin > p))
        currentMin = p
    })
  }

  override def next(): Product2[Array[Byte], Array[Byte]] = {
    /* now we need to walk over the list to find out where the min is */
    calcNextMinBuffer()
    /* now we have the min, we copy it out */
    processed += 1
    currentMin.buf.get(key)
    currentMin.buf.get(value)
    if(currentMin.buf.remaining() == 0) {
      /* we hit the end of the current buffer, time to refresh */
      val itr = bufferList.iterator
      val oldCurrentMin = currentMin
      /* find any buffer which has non-zero capacity */
      while (itr.hasNext && currentMin == oldCurrentMin){
        val x = itr.next()
        if(x.buf.remaining() > 0) {
          /* setting this will break the loop as well */
          currentMin = x
        }
      }
    }
    kv
  }
}