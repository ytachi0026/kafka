/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.log

import java.io._
import java.nio._
import java.nio.channels._
import java.util.concurrent.atomic._

import kafka.utils._
import kafka.message._
import kafka.common.KafkaException
import java.util.concurrent.TimeUnit
import kafka.metrics.{KafkaTimer, KafkaMetricsGroup}

/**
 * An on-disk message set. An optional start and end position can be applied to the message set
 * which will allow slicing a subset of the file.
 * @param file The file name for the underlying log data
 * @param channel the underlying file channel used
 * @param start A lower bound on the absolute position in the file from which the message set begins
 * @param end The upper bound on the absolute position in the file at which the message set ends
 * @param isSlice Should the start and end parameters be used for slicing?
 */
@nonthreadsafe
class FileMessageSet private[kafka](val file: File,
                                    private[log] val channel: FileChannel,
                                    private[log] val start: Int,
                                    private[log] val end: Int,
                                    isSlice: Boolean) extends MessageSet with Logging {
  
  /* the size of the message set in bytes */
  private val _size = 
    if(isSlice)
      new AtomicInteger(end - start) // don't check the file size if this is just a slice view
    else
      new AtomicInteger(math.min(channel.size().toInt, end) - start)

  /* if this is not a slice, update the file pointer to the end of the file */
  if (!isSlice)
    channel.position(channel.size)

  /**
   * Create a file message set with no slicing.
   */
  def this(file: File, channel: FileChannel) = 
    this(file, channel, start = 0, end = Int.MaxValue, isSlice = false)
  
  /**
   * Create a file message set with no slicing
   */
  def this(file: File) = 
    this(file, Utils.openChannel(file, mutable = true))
  
  /**
   * Create a slice view of the file message set that begins and ends at the given byte offsets
   */
  def this(file: File, channel: FileChannel, start: Int, end: Int) = 
    this(file, channel, start, end, isSlice = true)
  
  /**
   * Return a message set which is a view into this set starting from the given position and with the given size limit.
   * 
   * If the size is beyond the end of the file, the end will be based on the size of the file at the time of the read.
   * 
   * If this message set is already sliced, the position will be taken relative to that slicing.
   * 
   * @param position The start position to begin the read from
   * @param size The number of bytes after the start position to include
   * 
   * @return A sliced wrapper on this message set limited based on the given position and size
   */
  def read(position: Int, size: Int): FileMessageSet = {
    if(position < 0)
      throw new IllegalArgumentException("Invalid position: " + position)
    if(size < 0)
      throw new IllegalArgumentException("Invalid size: " + size)
    new FileMessageSet(file,
                       channel,
                       start = this.start + position,
                       end = math.min(this.start + position + size, sizeInBytes()))
  }
  
  /**
   * Search forward for the file position of the last offset that is great than or equal to the target offset 
   * and return its physical position. If no such offsets are found, return null.
   * @param targetOffset The offset to search for.
   * @param startingPosition The starting position in the file to begin searching from.
   */
  def searchFor(targetOffset: Long, startingPosition: Int): OffsetPosition = {
    var position = startingPosition
    val buffer = ByteBuffer.allocate(MessageSet.LogOverhead)
    val size = sizeInBytes()
    while(position + MessageSet.LogOverhead < size) {
      buffer.rewind()
      channel.read(buffer, position)
      if(buffer.hasRemaining)
        throw new IllegalStateException("Failed to read complete buffer for targetOffset %d startPosition %d in %s"
                                        .format(targetOffset, startingPosition, file.getAbsolutePath))
      buffer.rewind()
      val offset = buffer.getLong()
      if(offset >= targetOffset)
        return OffsetPosition(offset, position)
      val messageSize = buffer.getInt()
      position += MessageSet.LogOverhead + messageSize
    }
    null
  }
  
  /**
   * Write some of this set to the given channel.
   * @param destChannel The channel to write to.
   * @param writePosition The position in the message set to begin writing from.
   * @param size The maximum number of bytes to write
   * @return The number of bytes actually written.
   */
  def writeTo(destChannel: GatheringByteChannel, writePosition: Long, size: Int): Int =
    channel.transferTo(start + writePosition, math.min(size, sizeInBytes), destChannel).toInt
  
  /**
   * Get a shallow iterator over the messages in the set.
   */
  override def iterator() = iterator(Int.MaxValue)
    
  /**
   * Get an iterator over the messages in the set. We only do shallow iteration here.
   * @param maxMessageSize A limit on allowable message size to avoid allocating unbounded memory. 
   * If we encounter a message larger than this we throw an InvalidMessageException.
   * @return The iterator.
   */
  def iterator(maxMessageSize: Int): Iterator[MessageAndOffset] = {
    new IteratorTemplate[MessageAndOffset] {
      var location = start
      
      override def makeNext(): MessageAndOffset = {
        if(location >= end)
          return allDone()
          
        // read the size of the item
        val sizeOffsetBuffer = ByteBuffer.allocate(12)
        channel.read(sizeOffsetBuffer, location)
        if(sizeOffsetBuffer.hasRemaining)
          return allDone()
        
        sizeOffsetBuffer.rewind()
        val offset = sizeOffsetBuffer.getLong()
        val size = sizeOffsetBuffer.getInt()
        if(size < Message.MinHeaderSize)
          return allDone()
        if(size > maxMessageSize)
          throw new InvalidMessageException("Message size exceeds the largest allowable message size (%d).".format(maxMessageSize))
        
        // read the item itself
        val buffer = ByteBuffer.allocate(size)
        channel.read(buffer, location + 12)
        if(buffer.hasRemaining)
          return allDone()
        buffer.rewind()
        
        // increment the location and return the item
        location += size + 12
        new MessageAndOffset(new Message(buffer), offset)
      }
    }
  }
  
  /**
   * The number of bytes taken up by this file set
   */
  def sizeInBytes(): Int = _size.get()
  
  /**
   * Append these messages to the message set
   */
  def append(messages: ByteBufferMessageSet) {
    val written = messages.writeTo(channel, 0, messages.sizeInBytes)
    _size.getAndAdd(written)
  }
 
  /**
   * Commit all written data to the physical disk
   */
  def flush() = {
    channel.force(true)
  }
  
  /**
   * Close this message set
   */
  def close() {
    flush()
    channel.close()
  }
  
  /**
   * Delete this message set from the filesystem
   * @return True iff this message set was deleted.
   */
  def delete(): Boolean = {
    Utils.swallow(channel.close())
    file.delete()
  }

  /**
   * Truncate this file message set to the given size in bytes. Note that this API does no checking that the 
   * given size falls on a valid message boundary.
   * @param targetSize The size to truncate to.
   */
  def truncateTo(targetSize: Int) = {
    if(targetSize > sizeInBytes || targetSize < 0)
      throw new KafkaException("Attempt to truncate log segment to " + targetSize + " bytes failed, " +
                               " size of this log segment is " + sizeInBytes + " bytes.")
    channel.truncate(targetSize)
    channel.position(targetSize)
    _size.set(targetSize)
  }
  
  /**
   * Read from the underlying file into the buffer starting at the given position
   */
  def readInto(buffer: ByteBuffer, position: Int): ByteBuffer = {
    channel.read(buffer, position)
    buffer.rewind()
    buffer
  }
  
}

object LogFlushStats extends KafkaMetricsGroup {
  val logFlushTimer = new KafkaTimer(newTimer("LogFlushRateAndTimeMs", TimeUnit.MILLISECONDS, TimeUnit.SECONDS))
}
