/*
 * Copyright 2016 Midokura SARL
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
 */

package org.midonet.cluster.storage

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.ZkConnectionAwareWatcher
import org.midonet.cluster.data.storage.{DirectoryStateTable, StateTableEncoder}
import org.midonet.cluster.storage.ArpStateTable.ArpEncoder
import org.midonet.midolman.serialization.SerializationException
import org.midonet.midolman.state.{ArpCacheEntry, ReplicatedMap}
import org.midonet.packets.IPv4Addr

object ArpStateTable {

    trait ArpEncoder extends StateTableEncoder[IPv4Addr, ArpCacheEntry] {
        @inline protected override def encodeKey(address: IPv4Addr): String = {
            address.toString
        }

        @inline protected override def decodeKey(string: String): IPv4Addr = {
            IPv4Addr(string)
        }

        @inline protected override def encodeValue(entry: ArpCacheEntry): String = {
            entry.encode()
        }

        @throws[SerializationException]
        @inline protected override def decodeValue(string: String): ArpCacheEntry = {
            ArpCacheEntry.decode(string)
        }
    }
    object ArpEncoder extends ArpEncoder

}

final class ArpStateTable(override val directory: Directory,
                          zkConnWatcher: ZkConnectionAwareWatcher)
    extends DirectoryStateTable[IPv4Addr, ArpCacheEntry]
    with ReplicatedMapStateTable[IPv4Addr, ArpCacheEntry]
    with ArpEncoder {

    protected override val nullValue = null
    protected override val map = new ReplicatedMap[IPv4Addr, ArpCacheEntry](directory)
                                 with ArpEncoder

    if (zkConnWatcher ne null)
        map.setConnectionWatcher(zkConnWatcher)

}
