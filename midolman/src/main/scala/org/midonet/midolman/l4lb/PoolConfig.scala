/*
 * Copyright (c) 2014 Midokura Pte.Ltd.
 */
package org.midonet.midolman.l4lb

import java.util.UUID
import scala.collection.immutable.Set
import org.slf4j.{LoggerFactory, Logger}

/**
 * Represents a pool object local to the host.  The host that acts as a
 * health monitor only needs to know minimal amount of pool data to run the
 * service.
 */
class PoolConfig(val id: UUID, val vip: VipConfig,
                 val members: Set[PoolMemberConfig],
                 val healthMonitor: HealthMonitorConfig,
                 val adminStateUp: Boolean,
                 val l4lb_file_locs: String) {

    private final val log: Logger
        = LoggerFactory.getLogger(classOf[PoolConfig])

    val SOCKET = "sock"
    val CONF = "conf"
    val PID = "pid"

    def haproxyConfFileLoc = l4lb_file_locs + id.toString + "/" + CONF
    def haproxyPidFileLoc = l4lb_file_locs + id.toString + "/" + PID
    def haproxySockFileLoc = l4lb_file_locs + id.toString + "/" + SOCKET

    // make sure that the config has the necessary fields to write a
    // valid config
    def isConfigurable: Boolean =
        id != null && vip != null && healthMonitor != null &&
        healthMonitor.isConfigurable && vip.isConfigurable &&
        members.forall (_.isConfigurable)

    def generateConfigFile(): String = {
        if (!isConfigurable) {
            log.error("haproxy config not complete")
            return ""
        }
        val conf = new StringBuilder()
        conf append
s"""global
        daemon
        user nobody
        group nogroup
        log /dev/log local0
        log /dev/log local1 notice
        stats socket $haproxySockFileLoc mode 0666 level user
defaults
        log global
        retries 3
        timeout connect 5000
        timeout client 5000
        timeout server 5000
frontend ${vip.id.toString}
        option tcplog
        bind ${vip.ip}:${vip.port}
        mode tcp
        default_backend $id
backend $id
        timeout check ${healthMonitor.timeout}s
"""
        members.foreach(x => conf.append(s"        server ${x.id.toString} " +
            s"${x.address}:${x.port} check inter ${healthMonitor.delay}s " +
            s"fall ${healthMonitor.maxRetries}\n"))
        conf.toString()
    }
}
