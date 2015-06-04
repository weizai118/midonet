/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.util.collection

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations.{Setup => JmhSetup, _}
import org.openjdk.jmh.infra.Blackhole

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Benchmark)
@org.openjdk.jmh.annotations.Threads(1)
class IPv4InvalidationArrayBenchmark extends {

    var array: IPv4InvalidationArray = _

    var add = 0
    var del = 0

    val MAX_SIZE = 1000000

    @Param(Array("4", "17", "49", "151"))
    var step: Int = _

    @JmhSetup
    def setup(): Unit = {
        array = new IPv4InvalidationArray()
    }

    @Benchmark
    def benchmarkAddDelete(bh: Blackhole): Unit = {
        array.ref(add, 32)
        bh.consume(array(add))
        add += step
        if (((add - del) / step) > MAX_SIZE) {
            bh.consume(array.unref(del))
            del += step
        }
    }
}
