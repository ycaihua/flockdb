/*
 * Copyright 2010 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.flockdb.jobs

import com.twitter.gizzard.scheduler._
import com.twitter.gizzard.shards.ShardId
import com.twitter.gizzard.nameserver.NameServer
import com.twitter.results
import com.twitter.ostrich.Stats
import com.twitter.xrayspecs.TimeConversions._
import net.lag.logging.Logger
import shards.Shard


object UnsafeCopy {
  type Cursor = (results.Cursor, results.Cursor)

  val START = (results.Cursor.Start, results.Cursor.Start)
  val END = (results.Cursor.End, results.Cursor.End)
  val COUNT = 10000
}

class UnsafeCopyFactory(nameServer: NameServer[Shard], scheduler: JobScheduler[JsonJob])
      extends CopyJobFactory[Shard] {
  def apply(sourceShardId: ShardId, destinationShardId: ShardId) =
    new MetadataUnsafeCopy(sourceShardId, destinationShardId, MetadataUnsafeCopy.START,
                           UnsafeCopy.COUNT, nameServer, scheduler)
}

class UnsafeCopyParser(nameServer: NameServer[Shard], scheduler: JobScheduler[JsonJob])
      extends CopyJobParser[Shard] {
  def deserialize(attributes: Map[String, Any], sourceId: ShardId, destinationId: ShardId, count: Int) = {
    val cursor = (results.Cursor(attributes("cursor1").asInstanceOf[AnyVal].toInt),
                  results.Cursor(attributes("cursor2").asInstanceOf[AnyVal].toInt))
    new UnsafeCopy(sourceId, destinationId, cursor, count, nameServer, scheduler)
  }
}

class UnsafeCopy(sourceShardId: ShardId, destinationShardId: ShardId, cursor: UnsafeCopy.Cursor,
                 count: Int, nameServer: NameServer[Shard], scheduler: JobScheduler[JsonJob])
      extends CopyJob[Shard](sourceShardId, destinationShardId, count, nameServer, scheduler) {
  def copyPage(sourceShard: Shard, destinationShard: Shard, count: Int) = {
    val (items, newCursor) = sourceShard.selectAll(cursor, count)
    destinationShard.bulkUnsafeInsertEdges(items)
    Stats.incr("edges-copy", items.size)
    if (newCursor == UnsafeCopy.END) {
      None
    } else {
      Some(new UnsafeCopy(sourceShardId, destinationShardId, newCursor, count, nameServer, scheduler))
    }
  }

  def serialize = Map("cursor1" -> cursor._1.position, "cursor2" -> cursor._2.position)
}

object MetadataUnsafeCopy {
  type Cursor = results.Cursor
  val START = results.Cursor.Start
  val END = results.Cursor.End
}

class MetadataUnsafeCopyParser(nameServer: NameServer[Shard], scheduler: JobScheduler[JsonJob])
      extends CopyJobParser[Shard] {
  def deserialize(attributes: Map[String, Any], sourceId: ShardId, destinationId: ShardId, count: Int) = {
    val cursor = results.Cursor(attributes("cursor").asInstanceOf[AnyVal].toInt)
    new MetadataUnsafeCopy(sourceId, destinationId, cursor, count, nameServer, scheduler)
  }
}

class MetadataUnsafeCopy(sourceShardId: ShardId, destinationShardId: ShardId,
                         cursor: MetadataUnsafeCopy.Cursor, count: Int,
                         nameServer: NameServer[Shard], scheduler: JobScheduler[JsonJob])
      extends CopyJob[Shard](sourceShardId, destinationShardId, count, nameServer, scheduler) {
  def copyPage(sourceShard: Shard, destinationShard: Shard, count: Int) = {
    val (items, newCursor) = sourceShard.selectAllMetadata(cursor, count)
    destinationShard.bulkUnsafeInsertMetadata(items)
    Stats.incr("edges-copy", items.size)
    if (newCursor == MetadataUnsafeCopy.END)
      Some(new UnsafeCopy(sourceShardId, destinationShardId, UnsafeCopy.START, UnsafeCopy.COUNT,
                          nameServer, scheduler))
    else
      Some(new MetadataUnsafeCopy(sourceShardId, destinationShardId, newCursor, count, nameServer, scheduler))
  }

  def serialize = Map("cursor" -> cursor.position)
}