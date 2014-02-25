/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.l4lb

import akka.actor._

import java.io._
import java.nio.ByteBuffer
import java.nio.channels.IllegalSelectorException
import java.nio.channels.spi.SelectorProvider
import java.util.UUID

import org.midonet.midolman.l4lb.HaproxyHealthMonitor._
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.CheckHealth
import org.midonet.midolman.l4lb.HaproxyHealthMonitor.ConfigUpdate
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.routingprotocols.IP
import org.midonet.netlink.{AfUnix, NetlinkSelectorProvider, UnixDomainChannel}

import scala.collection.JavaConversions._
import scala.collection.mutable.HashSet
import scala.collection.mutable.Set
import scala.concurrent.duration._
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.Route
import org.midonet.cluster.data.ports.RouterPort
import org.midonet.midolman.state.PoolMemberStatus
import org.midonet.midolman.state.PoolMemberStatus.{UP => MemberUp}
import org.midonet.midolman.state.PoolMemberStatus.{DOWN => MemberDown}


/**
 * Actor that represents the interaction with Haproxy for health monitoring.
 * Creating this actor is basically synonymous with creating a health monitor,
 * so we handle all of the setup in preStart, and all of the cleanup in
 * postStop. Other than that, the only options for interaction with this
 * actor is telling it to stop/start monitoring, and receiving updates.
 */
object HaproxyHealthMonitor {
    def props(config: PoolConfig, manager: ActorRef, routerId: UUID,
              client: DataClient, hostId: UUID): Props = Props(
                  new HaproxyHealthMonitor(config, manager, routerId, client,
                                           hostId))


    sealed trait HHMMessage
    // Tells this actor to start polling haproxy for aliveness.
    case object StartMonitor extends HHMMessage
    // Tells this actor to stop polling haproxy.
    case object StopMonitor extends HHMMessage
    // This is a way of alerting the manager that setup has failed
    case object SetupFailure extends HHMMessage
    // This is a way of alerting the manager that it was unable to get
    // health information from Haproxy
    case object SockReadFailure extends HHMMessage
    // Tells this actor that the config for the health monitor has changed.
    case class ConfigUpdate(conf: PoolConfig) extends HHMMessage
    // Tells this actor to poll haproxy once for health info.
    private[HaproxyHealthMonitor] case object CheckHealth extends HHMMessage

    // Constants for linking namespace to router port
    val NameSpaceIp = "169.254.17.45"
    val RouterIp = "169.254.17.44"
    val NetLen = 30
    val NetAddr = "169.254.17.43"

    // Constants used in parsing haproxy output
    val StatusPos = 17
    val NamePos = 1
    val Backend = "BACKEND"
    val Frontend = "FRONTEND"
    val StatusUp = "UP"
    val StatusDown = "DOWN"
    val FieldName = "svname"
    val ShowStat = "show stat\n"
}

class HaproxyHealthMonitor(var config: PoolConfig,
                           val manager: ActorRef,
                           val routerId: UUID,
                           val client: DataClient,
                           val hostId: UUID)
    extends Actor with ActorLogWithoutPath with Stash {
    implicit def system: ActorSystem = context.system
    implicit def executor = system.dispatcher

    private var pollingActive = false
    private var currentUpNodes = Set[UUID]()
    private var currentDownNodes = Set[UUID]()
    private val healthMonitorName = config.id.toString.substring(0,8)
    private var routerPortId: UUID = null

    override def preStart(): Unit = {
        try {
            writeConf(config)
            val nsName = createNamespace(healthMonitorName, config.vip.ip)
            hookNamespaceToRouter(nsName, routerId)
            restartHaproxy(healthMonitorName, config.haproxyConfFileLoc,
                           config.haproxyPidFileLoc)
        } catch {
            case e: Exception =>
                log.error("Unable to create Health Monitor for " +
                          config.haproxyConfFileLoc)
                manager ! SetupFailure
        }
    }

    override def postStop(): Unit = {
        unhookNamespaceFromRouter()
        removeNamespace(healthMonitorName)
    }

    def receive = {
        case StartMonitor =>
            system.scheduler.scheduleOnce(1 second, self, CheckHealth)
            pollingActive = true

        case StopMonitor => pollingActive = false

        case ConfigUpdate(conf) =>
            config = conf
            try {
                writeConf(config)
                restartHaproxy(healthMonitorName,
                               conf.haproxyConfFileLoc,
                               conf.haproxyPidFileLoc)
            } catch {
                case e: Exception =>
                    log.error("Unable to update Health Monitor for " +
                              config.haproxyConfFileLoc)
                    manager ! SetupFailure
            }

        case CheckHealth =>
            try {
                val statusInfo = getHaproxyStatus(config.haproxySockFileLoc)
                val (upNodes, downNodes) = parseResponse(statusInfo)
                val newUpNodes = upNodes diff currentUpNodes
                val newDownNodes = downNodes diff currentDownNodes

                def updateClient(id: UUID, status: PoolMemberStatus): Unit = {
                    val member = client.poolMemberGet(id)
                    if (member == null) {
                        log.error("pool member " + id.toString +
                                  " not in database")
                        return Unit
                    }
                    member.setStatus(status)
                    client.poolMemberUpdate(member)
                }
                newUpNodes foreach (id => updateClient(id, MemberUp))
                newDownNodes foreach (id => updateClient(id, MemberDown))

                currentUpNodes = upNodes
                currentDownNodes = downNodes
            } catch {
                case e: Exception =>
                    log.error("Unable to retrieve health information for "
                              + config.haproxySockFileLoc)
                    manager ! SockReadFailure
            }
            if (pollingActive) {
                system.scheduler.scheduleOnce(1 second, self, CheckHealth)
            }
    }

    /*
     * Take the output from the haproxy response and turn it into a set
     * of UP member ids. Assumes the following:
     * 1) The names of the hosts are the id's of the members.
     * 2) The format is "show stat" response.
     * Example of a line of the output:
     * backend_id,name_of_server,0,0,0,0,,0,0,0,,0,,0,0,0,0,DOWN,1,1,0,0,1,
     * 2411,2411,,1,2,2,,0,,2,0,,0,L4CON,,1999,,,,,,,0,,,,0,0,
     */
    private def parseResponse(resp: String): (Set[UUID], Set[UUID]) = {
        if (resp == null) {
            (currentUpNodes, currentDownNodes)
        }

        val upNodes = new HashSet[UUID]()
        val downNodes = new HashSet[UUID]()

        def isMemberEntry(entry: Array[String]): Boolean =
            entry.length > StatusPos &&
            entry(NamePos) != Backend &&
            entry(NamePos) != Frontend &&
            entry(NamePos) != FieldName

        def isUp(entry: Array[String]): Boolean =
            isMemberEntry(entry) && entry(StatusPos) == StatusUp

        def isDown(entry: Array[String]): Boolean =
            isMemberEntry(entry) && entry(StatusPos) == StatusDown

        val entries = resp split "\n" map (_.split(","))
        entries foreach {
            case entry if isUp(entry) =>
                upNodes add UUID.fromString(entry(NamePos))
            case entry if isDown(entry) =>
                downNodes add UUID.fromString(entry(NamePos))
        }
        (upNodes, downNodes)
    }

    /* ======================================================================
     * BLOCKING CALLS: functions that may block, and therefore need to be
     * called from within a different execution context. These are mostly
     * IO operations.
     */
    def writeConf(config: PoolConfig): Unit = {
        val file = new File(config.haproxyConfFileLoc)
        file.getParentFile.mkdirs()
        val writer = new PrintWriter(file, "UTF-8")
        try {
            writer.print(config.generateConfigFile())
        } catch {
            case fnfe: FileNotFoundException =>
                log.error("FileNotFoundException while trying to write " +
                          config.haproxyConfFileLoc)
                throw fnfe
            case uee: UnsupportedEncodingException =>
                log.error("UnsupportedEncodingException while trying to " +
                          "write " + config.haproxyConfFileLoc)
                throw uee
        } finally {
            writer.close()
        }
    }

    /*
     * Creates a new namespace and hooks up an interface.
     */
    def createNamespace(name: String, ip: String): String = {
        /*
         * TODO: We should not be calling ip commands directly from midolman
         * because it depends on on the ip package being installed. We will
         * later fix this to call the kernel utility directly.
         */
        val dp = name + "_dp"
        val ns = name + "_ns"
        val iptablePost = "iptables --table nat --append POSTROUTING " +
                          "--source " + NameSpaceIp + " --jump SNAT " +
                          "--to-source " + ip
        val iptablePre = "iptables --table nat --append PREROUTING" +
                         " --destination " + ip + " --jump DNAT " +
                         "--to-destination " + NameSpaceIp
        IP.ensureNamespace(name)
        try {
            IP.link("add name " + dp + " type veth peer name " + ns)
            IP.link("set " + dp + " up")
            IP.link("set " + ns + " netns " + name)
            IP.execIn(name, "ip link set " + ns + " up")
            IP.execIn(name, "ip address add " + ip + "/24 dev " + ns)
            IP.execIn(name, "ifconfig lo up")
            IP.execIn(name, iptablePre)
            IP.execIn(name, iptablePost)
        } catch {
            case e: Exception =>
                removeNamespace(name)
                throw e
        }
        dp
    }

    def removeNamespace(name: String): Unit = {
        if (IP.namespaceExist(name)) {
            val ns = name + "_ns"
            if (IP.interfaceExistsInNs(name, ns)) {
                IP.execIn(name, "ip link delete " + ns)
            }
            if (haproxyIsRunning()) {
                killHaproxy()
            }
            IP.deleteNS(name)
        }
    }

    def pidMatchesWithArgs(pid: Int): Boolean = {
        val filePath = "/proc/" + pid + "/cmdline"
        val file = new File(filePath)
        if (!file.exists()) return false
        val fileReader = new FileReader(filePath)
        val bufReader = new BufferedReader(fileReader)
        var pidString = bufReader.readLine()
        pidString = pidString.replace('\0', ' ')
        pidString.contains(haproxyCommandLineWithoutPid)
    }

    def haproxyIsRunning(): Boolean = {
        val pid = haproxyPid()
        if (pid != -1) {
            pidMatchesWithArgs(pid)
        } else {
            false
        }
    }

    def haproxyCommandLineWithoutPid = "haproxy -f " +
        config.haproxyConfFileLoc + " -p " + config.haproxyPidFileLoc


    def haproxyCommandLine(): String = {
        if (haproxyPid() != -1)
            haproxyCommandLineWithoutPid + " -sf " + haproxyPid().toString
        else
            haproxyCommandLineWithoutPid
    }

    def killHaproxy(): Unit = {
        val pid = haproxyPid()
        if (pid != -1) {
            // sanity check. The pid needs to exist and match up with the
            // command line.
            if (pidMatchesWithArgs(pid)) {
                IP.execIn(healthMonitorName, "kill -15 " + pid)
                if (!pidMatchesWithArgs(pid))
                    return
                Thread.sleep(200)
                if (!pidMatchesWithArgs(pid))
                    return
                Thread.sleep(5000)
                if (!pidMatchesWithArgs(pid))
                    return
                IP.execIn(healthMonitorName, "kill -9" + pid)
                Thread.sleep(200)
                if (!pidMatchesWithArgs(pid))
                    return
                log.error("Unable to kill haproxy process " + pid)
            } else {
                log.error("pid " + pid + " does not match up with contents " +
                          " of " + config.haproxyPidFileLoc)
            }
        }
    }

    // we keep the pid of haproxy in the pid file, so just dump that file.
    def haproxyPid(): Int = {
        try {
            val fileReader = new FileReader(config.haproxyPidFileLoc)
            val bufReader = new BufferedReader(fileReader)
            val pidString = bufReader.readLine()
            pidString.toInt
        } catch {
            case ioe: IOException =>
                log.error("Unable to get pid info from " +
                          config.haproxyPidFileLoc)
                -1
            case nfe: NumberFormatException =>
                log.error("The pid in " + config.haproxyPidFileLoc +
                          " is malformed")
                -1
        }
    }

    /*
     * This will restart haproxy with the given config file.
     */
    def restartHaproxy(name: String, confFileLoc: String,
                       pidFileLoc: String) {
        killHaproxy()
        IP.execIn(name, haproxyCommandLine())
    }

    def makeChannel(): UnixDomainChannel = SelectorProvider.provider() match {
        case nl: NetlinkSelectorProvider =>
            nl.openUnixDomainSocketChannel(AfUnix.Type.SOCK_STREAM)
        case other =>
          log.error("Invalid selector type: {} => jdk-bootstrap shadowing " +
                    "may have failed ?", other.getClass)
          throw new IllegalSelectorException
    }

    /*
     * Asks the given socket for haproxy info. This creates a new channel
     * Everytime because Haproxy will close the connection after a write/read
     * If necessary, we can get around this by maintaining a connection
     * by operation in "command line mode" for haproxy.
     */
    def getHaproxyStatus(path: String) : String = {
        val socketFile = new File(path)
        val socketAddress = new AfUnix.Address(socketFile.getAbsolutePath)
        val chan = makeChannel()
        chan.connect(socketAddress)
        val wb = ByteBuffer.wrap(ShowStat.getBytes)
        while(wb.hasRemaining)
            chan.write(wb)
        val data = new StringBuilder
        val buf = ByteBuffer.allocate(1024)
        while (chan.read(buf) > 0) {
            data append new String(buf.array(), "ASCII")
            buf.clear()
        }
        chan.close()
        data.toString()
    }

    def hookNamespaceToRouter(nsName: String, routerId: UUID) = {
        val ports = client.portsFindByRouter(routerId)
        var portId: UUID = null

        // see if the port already exists, and delete it if it does. This can
        // happen if there was already an haproxy attached to this router.
        for (port <- ports) {
            port match {
                case rpc: RouterPort =>
                    if (rpc.getNwAddr == RouterIp) {
                        client.hostsDelVrnPortMapping(hostId, rpc.getId())
                        portId = rpc.getId()
                    }
            }
        }

        if (portId == null) {
            val routerPort = new RouterPort()
            routerPort.setDeviceId(routerId)
            routerPort.setHostId(hostId)
            routerPort.setPortAddr(RouterIp)
            routerPort.setNwAddr(NetAddr)
            routerPort.setNwLength(NetLen)
            routerPort.setInterfaceName(nsName)
            portId = client.portsCreate(routerPort)
        }

        // Add a route to this port for the VIP. Make it ephermeral because
        // if this node goes down then this port the traffic gets routed to
        // is no longer valid.
        val route = new Route()
        route.setRouterId(routerId)
        route.setNextHopPort(portId)
        route.setSrcNetworkAddr("0.0.0.0")
        route.setSrcNetworkLength(0)
        route.setDstNetworkAddr(config.vip.ip)
        route.setDstNetworkLength(32)
        route.setWeight(100)
        client.routesCreateEphemeral(route)

        client.hostsAddVrnPortMapping(hostId, portId, nsName)
        routerPortId = portId
    }

    def unhookNamespaceFromRouter() = {
        if (routerPortId != null) {
            client.hostsDelVrnPortMapping(hostId, routerPortId)
            client.portsDelete(routerPortId)
        }
    }
}
