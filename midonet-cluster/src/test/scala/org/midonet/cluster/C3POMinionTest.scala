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

package org.midonet.cluster

import java.util.UUID

import scala.collection.JavaConverters._
import scala.concurrent.Future

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.{JsonNodeFactory, ObjectNode}
import com.google.protobuf.Message
import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.mockito.Matchers.any
import org.mockito.Mockito
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.data.neutron.NeutronResourceType
import org.midonet.cluster.data.neutron.NeutronResourceType.{AgentMembership => AgentMembershipType, BgpPeer => BgpPeerType, Config => ConfigType, Firewall => FirewallType, Network => NetworkType, Port => PortType, Router => RouterType, Subnet => SubnetType}
import org.midonet.cluster.data.storage.{InMemoryStorage, StateTableStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons._
import org.midonet.cluster.models.Neutron.NeutronConfig.TunnelProtocol
import org.midonet.cluster.models.Neutron.NeutronPort.{DeviceOwner, ExtraDhcpOpts}
import org.midonet.cluster.models.Neutron.{NeutronNetwork, NeutronRoute, NeutronRouter}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.rest_api.neutron.models.BgpPeer.AuthType
import org.midonet.cluster.rest_api.neutron.models.RuleProtocol
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Create, Delete, Update}
import org.midonet.cluster.services.c3po.{NeutronDeserializer, NeutronTranslatorManager}
import org.midonet.cluster.storage._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, SequenceDispenser}
import org.midonet.packets.{IPSubnet, IPv4Subnet, MAC}
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.toFutureOps

/** Tests the service that syncs the Neutron DB into Midonet's backend. */
class C3POMinionTestBase extends FlatSpec with BeforeAndAfter
                                          with BeforeAndAfterAll
                                          with Matchers
                                          with MidonetEventually {

    protected val log = LoggerFactory.getLogger(this.getClass)

    protected val nodeFactory = new JsonNodeFactory(true)

    protected var config: ClusterConfig = _
    protected var backend: MidonetBackend = _
    protected var sequenceDispenser: SequenceDispenser = _
    protected var manager: NeutronTranslatorManager = _

    protected def storage: Storage = backend.store
    protected def stateTableStorage: StateTableStorage = backend.stateTableStore
    protected def directory: Directory =
        backend.store.asInstanceOf[InMemoryStorage].tablesDirectory

    before {
        config = ClusterConfig.forTests(ConfigFactory.empty())
        backend = new MidonetTestBackend()
        sequenceDispenser = Mockito.mock(classOf[SequenceDispenser])

        Mockito.when(sequenceDispenser.next(any()))
               .thenReturn(Future.successful(1))
        Mockito.when(sequenceDispenser.current(any()))
               .thenReturn(Future.successful(1))

        manager = new NeutronTranslatorManager(config, backend, sequenceDispenser)

        backend.startAsync().awaitRunning()
    }

    after {
        backend.stopAsync().awaitTerminated()
    }

    protected def insertCreateTask(taskId: Int,
                                   rsrcType: NeutronResourceType[_ <: Message],
                                   json: JsonNode, rsrcId: UUID): Unit = {
        val op = Create(NeutronDeserializer.toMessage(json.toString,
                                                      rsrcType.clazz))
        storage.tryTransaction { tx =>
            manager.translate(tx, op)
        }
    }

    protected def insertUpdateTask(taskId: Int,
                                   rsrcType: NeutronResourceType[_ <: Message],
                                   json: JsonNode, rsrcId: UUID): Unit = {
        val op = Update(NeutronDeserializer.toMessage(json.toString,
                                                      rsrcType.clazz))
        storage.tryTransaction { tx =>
            manager.translate(tx, op)
        }
    }

    protected def insertDeleteTask(taskId: Int,
                                   rsrcType: NeutronResourceType[_ <: Message],
                                   rsrcId: UUID): Unit = {
        val op = Delete(rsrcType.clazz, rsrcId)
        storage.tryTransaction { tx =>
            manager.translate(tx, op)
        }
    }

    protected def poolJson(id: UUID, routerId: UUID,
                           adminStateUp: Boolean = true,
                           healthMonitorIds: Seq[UUID] = Nil): JsonNode = {
        val lb = nodeFactory.objectNode
        lb.put("id", id.toString)
        lb.put("router_id", routerId.toString)
        lb.put("admin_state_up", adminStateUp)
        if (healthMonitorIds.nonEmpty) {
            val hmIds = lb.putArray("health_monitors")
            for (hmId <- healthMonitorIds)
                hmIds.add(hmId.toString)
        }
        lb
    }

    case class IPAlloc(ipAddress: String, subnetId: UUID)
    case class AddrPair(cidr: String, mac: String)
    protected def portJson(id: UUID,
                           networkId: UUID,
                           name: String = null,
                           adminStateUp: Boolean = true,
                           macAddr: String = MAC.random().toString,
                           fixedIps: Seq[IPAlloc] = null,
                           deviceId: UUID = null,
                           deviceOwner: DeviceOwner = null,
                           tenantId: String = "tenant",
                           securityGroups: Seq[UUID] = null,
                           hostId: UUID = null,
                           ifName: String = null,
                           allowedAddrPairs: Seq[AddrPair] = null,
                           portSecurityEnabled: Boolean = true,
                           extraOpt: List[ExtraDhcpOpts] = null)
    : JsonNode = {
        val p = nodeFactory.objectNode
        p.put("id", id.toString)
        p.put("network_id", networkId.toString)
        p.put("admin_state_up", adminStateUp)
        p.put("mac_address", macAddr)
        p.put("port_security_enabled", portSecurityEnabled)
        if (name != null) p.put("name", name)
        if (fixedIps != null) {
            val fi = p.putArray("fixed_ips")
            for (fixedIp <- fixedIps) {
                val ip = nodeFactory.objectNode
                ip.put("ip_address", fixedIp.ipAddress)
                ip.put("subnet_id", fixedIp.subnetId.toString)
                fi.add(ip)
            }
        }
        if (deviceId != null) p.put("device_id", deviceId.toString)
        if (deviceOwner != null) p.put("device_owner", deviceOwner.toString)
        if (tenantId != null) p.put("tenant_id", tenantId)
        if (securityGroups != null) {
            val sgList = p.putArray("security_groups")
            securityGroups.foreach(sgid => sgList.add(sgid.toString))
        }
        if (extraOpt != null) {
            val eoList = p.putArray("extra_dhcp_opts")
            for (eo <- extraOpt) {
                val eoN = nodeFactory.objectNode
                eoN.put("opt_name", eo.getOptName)
                eoN.put("opt_value", eo.getOptValue)
                eoList.add(eoN)
            }
        }
        if (hostId != null) p.put("binding:host_id", hostId.toString)
        if (ifName != null)
            p.putObject("binding:profile").put("interface_name", ifName)
        if (allowedAddrPairs != null) {
            val pairs = p.putArray("allowed_address_pairs")
            for (pair <- allowedAddrPairs) {
                val p = nodeFactory.objectNode
                p.put("ip_address", pair.cidr)
                p.put("mac_address", pair.mac)
                pairs.add(p)
            }
        }
        p
    }

    protected def sgJson(id: UUID,
                         name: String = null,
                         desc: String = null,
                         tenantId: String = null,
                         rules: Seq[JsonNode] = Seq()): JsonNode = {
        val sg = nodeFactory.objectNode
        sg.put("id", id.toString)
        sg.put("name", if (name != null) name else s"security-group-$id")
        if (desc != null) sg.put("description", desc)
        if (tenantId != null) sg.put("tenant_id", tenantId)
        if (rules != null)
            sg.putArray("security_group_rules").addAll(rules.asJava)
        sg
    }

    protected def ruleJson(id: UUID, sgId: UUID,
                           direction: RuleDirection = RuleDirection.INGRESS,
                           etherType: EtherType = EtherType.IPV4,
                           protocol: Protocol = Protocol.TCP,
                           portRange: Range = null,
                           remoteSgId: UUID = null,
                           remoteIpPrefix: IPSubnet[_] = null): JsonNode = {
        val r = nodeFactory.objectNode
        r.put("id", id.toString)
        r.put("security_group_id", sgId.toString)
        r.put("direction", direction.toString)
        if (etherType != null)
            r.put("ethertype", etherType.toString)
        if (protocol != null)
            r.put("protocol", protocol.toString)
        if (portRange != null) {
            r.put("port_range_min", portRange.start)
            r.put("port_range_max", portRange.end)
        }
        if (remoteSgId != null)
            r.put("remote_group_id", remoteSgId.toString)
        if (remoteIpPrefix != null)
            r.put("remote_ip_prefix", remoteIpPrefix.toString)
        r
    }

    protected def routerJson(id: UUID,
                             name: String = null,
                             adminStateUp: Boolean = true,
                             status: String = null,
                             tenantId: String = "tenant",
                             gwPortId: UUID = null,
                             enableSnat: Boolean = false,
                             extGwNetworkId: UUID = null,
                             routes: List[NeutronRoute] = null): JsonNode = {
        val r = nodeFactory.objectNode
        r.put("name", name)
        r.put("id", id.toString)
        r.put("admin_state_up", adminStateUp)
        if (status != null) r.put("status", status)
        if (tenantId != null) r.put("tenant_id", tenantId)
        if (gwPortId != null) r.put("gw_port_id", gwPortId.toString)
        if (enableSnat || extGwNetworkId != null) {
            val egi = nodeFactory.objectNode
            if (extGwNetworkId != null)
                egi.put("network_id", extGwNetworkId.toString)
            egi.put("enable_snat", enableSnat)
            r.set("external_gateway_info", egi)
        }
        if (routes != null) {
            val routesNode = r.putArray("routes")
            for (route <- routes) {
                val node = nodeFactory.objectNode
                node.put("destination",
                         IPSubnetUtil.fromProto(route.getDestination).toString)
                node.put("nexthop",
                         IPAddressUtil.toIPAddr(route.getNexthop).toString)
                routesNode.add(node)
            }
        }
        r
    }

    protected def routerInterfaceJson(routerId: UUID, portId: UUID,
                                      subnetId: UUID, tenantId: String = null)
    : JsonNode = {
        val ri = nodeFactory.objectNode
        ri.put("id", routerId.toString)
        ri.put("port_id", portId.toString)
        ri.put("subnet_id", subnetId.toString)
        if (tenantId != null) ri.put("tenant_id", tenantId)
        ri
    }

    protected def networkJson(id: UUID, tenantId: String = "tenant",
                              name: String = null,
                              shared: Boolean = false,
                              adminStateUp: Boolean = true,
                              external: Boolean = false,
                              uplink: Boolean = false): JsonNode = {
        val n = nodeFactory.objectNode
        n.put("id", id.toString)
        if (tenantId != null) n.put("tenant_id", tenantId)
        if (name != null) n.put("name", name)
        n.put("admin_state_up", adminStateUp)
        n.put("external", external)
        if (uplink) n.put("provider:network_type", "uplink")
        n
    }

    protected def configJson(id: UUID,
                             tunnelProtocol: TunnelProtocol): JsonNode = {
        val c = nodeFactory.objectNode
        c.put("id", id.toString)
        c.put("tunnel_protocol", tunnelProtocol.toString)
        c
    }

    protected def agentMembershipJson(id: UUID, ipAddress: String): JsonNode = {
        val c = nodeFactory.objectNode
        c.put("id", id.toString)
        c.put("ip_address", ipAddress)
        c
    }

    protected def firewallJson(id: UUID,
                               tenantId: String = "tenant",
                               adminStateUp: Boolean = true,
                               firewallRuleList: List[JsonNode] = List(),
                               addRouterIds: List[UUID] = List(),
                               delRouterIds: List[UUID] = List()): JsonNode = {
        val f = nodeFactory.objectNode()
        f.put("id", id.toString)
        f.put("tenant_id", tenantId)
        f.put("admin_state_up", adminStateUp)
        f.putArray("firewall_rule_list").addAll(firewallRuleList.asJava)
        val addRouterArray = f.putArray("add-router-ids")
        for (addRouterId <- addRouterIds) {
            addRouterArray.add(addRouterId.toString)
        }
        val delRouterArray = f.putArray("del-router-ids")
        for (delRouterId <- delRouterIds) {
            delRouterArray.add(delRouterId.toString)
        }
        f
    }

    protected def firewallUpdateJson(id: UUID,
                                     tenantId: String = "tenant",
                                     adminStateUp: Boolean = true,
                                     firewallRuleList: List[JsonNode] = List(),
                                     addRouterIds: List[UUID] = List(),
                                     delRouterIds: List[UUID] = List(),
                                     lastRouter: Boolean = false): JsonNode = {
        val f = firewallJson(id, tenantId, adminStateUp, firewallRuleList,
                             addRouterIds, delRouterIds)
        // This field only exists for Update
        f.asInstanceOf[ObjectNode].put("last-router", lastRouter)
    }

    protected def firewallRuleJson(id: UUID,
                                   tenantId: String = "tenant",
                                   protocol: RuleProtocol = RuleProtocol.TCP,
                                   ipVersion: Int = 4,
                                   sourceIpAddress: String = "10.0.0.0/24",
                                   destinationIpAddress: String = "20.0.0.2",
                                   sourcePort: String = "22",
                                   destinationPort: String = "8080:8081",
                                   action: String = "deny",
                                   enabled: Boolean = true,
                                   shared: Boolean = false,
                                   position: Int = 1): JsonNode = {
        val r = nodeFactory.objectNode()
        r.put("id", id.toString)
        r.put("tenant_id", tenantId)
        r.put("protocol", protocol.value())
        r.put("ip_version", ipVersion)
        r.put("source_ip_address", sourceIpAddress)
        r.put("destination_ip_address", destinationIpAddress)
        r.put("source_port", sourcePort)
        r.put("destination_port", destinationPort)
        r.put("action", action)
        r.put("enabled", enabled)
        r.put("shared", shared)
        r.put("position", position)
    }

    protected case class HostRoute(destination: String, nextHop: String)

    protected def subnetJson(id: UUID, networkId: UUID,
                             tenantId: String = "tenant",
                             name: String = null, cidr: String = null,
                             ipVersion: Int = 4, gatewayIp: String = null,
                             enableDhcp: Boolean = true,
                             dnsNameservers: List[String] = null,
                             hostRoutes: List[HostRoute] = null): JsonNode = {
        val s = nodeFactory.objectNode
        s.put("id", id.toString)
        s.put("network_id", networkId.toString)
        s.put("tenant_id", tenantId)
        if (name != null) s.put("name", name)
        if (cidr != null) s.put("cidr", cidr)
        s.put("ip_version", ipVersion)
        if (gatewayIp != null) s.put("gateway_ip", gatewayIp)
        s.put("enable_dhcp", enableDhcp)
        if (dnsNameservers != null) {
            val nameServers = s.putArray("dns_nameservers")
            for (nameServer <- dnsNameservers) {
                nameServers.add(nameServer)
            }
        }
        if (hostRoutes != null) {
            val routes = s.putArray("host_routes")
            for (route <- hostRoutes) {
                val r = nodeFactory.objectNode
                r.put("destination", route.destination)
                r.put("nexthop", route.nextHop)
                routes.add(r)
            }
        }

        s
    }

    protected def portBindingJson(id: UUID,
                                  hostId: UUID,
                                  interfaceName: String,
                                  portId: UUID): JsonNode = {
        val pb = nodeFactory.objectNode
        pb.put("id", id.toString)
        pb.put("host_id", hostId.toString)
        pb.put("interface_name", interfaceName)
        pb.put("port_id", portId.toString)
        pb
    }

    protected def vpnServiceJson(id: UUID,
                                 routerId: UUID,
                                 adminStateUp: Boolean = true,
                                 description: Option[String] = None,
                                 externalV4Ip: Option[String] = None,
                                 externalV6Ip: Option[String] = None,
                                 name: Option[String] = None,
                                 status: String = "PENDING_CREATE",
                                 tenantId: Option[String] = None): JsonNode = {
        val vpn = nodeFactory.objectNode
        vpn.put("id", id.toString)
        vpn.put("router_id", routerId.toString)
        vpn.put("admin_state_up", adminStateUp)
        vpn.put("description", description.getOrElse(s"VPNService with ID $id"))
        vpn.put("external_v4_ip", externalV4Ip.orNull)
        vpn.put("external_v6_ip", externalV6Ip.orNull)
        vpn.put("name", name.getOrElse(s"vpn-$id"))
        vpn.put("status", status)
        vpn.put("tenant_id", tenantId.orNull)
        vpn
    }

    protected def ipSecSiteConnectionJson(id: UUID,
                                          vpnServiceId: UUID,
                                          localCidrs: Seq[String],
                                          peerCidrs: Seq[String],
                                          ikePolicy: JsonNode,
                                          ipSecPolicy: JsonNode,
                                          adminStateUp: Boolean = true,
                                          authMode: String = "psk",
                                          description: Option[String] = None,
                                          dpdAction: String = "hold",
                                          dpdInterval: Int = 30,
                                          dpdTimeout: Int = 120,
                                          externalIp: Option[String] = None,
                                          initiator: String = "bi-directional",
                                          localEpGroupId: Option[UUID] = None,
                                          localIpVers: Int = 4,
                                          mtu: Int = 1500,
                                          name: Option[String] = None,
                                          peerAddress: String = "10.0.0.1",
                                          peerEpGroupId: Option[UUID] = None,
                                          peerId: String = "10.0.0.2",
                                          psk: String = "t0p_secret",
                                          routeMode: String = "static",
                                          status: String = "PENDING_CREATE",
                                          tenantId: Option[String] = None)
    : JsonNode = {
        val con = nodeFactory.objectNode
        con.put("id", id.toString)
        con.put("vpnservice_id", vpnServiceId.toString)
        val localCidrsArray = con.putArray("local_cidrs")
        for (cidr <- localCidrs) localCidrsArray.add(cidr)
        val peerCidrsArray = con.putArray("peer_cidrs")
        for (cidr <- peerCidrs) peerCidrsArray.add(cidr)
        con.set("ikepolicy", ikePolicy)
        con.set("ipsecpolicy", ipSecPolicy)
        con.put("admin_state_up", adminStateUp)
        con.put("auth_mode", authMode)
        con.put("description",
                description.getOrElse(s"IPSecSiteConnection with ID $id"))
        con.put("dpd_action", dpdAction)
        con.put("dpd_interval", dpdInterval)
        con.put("dpd_timeout", dpdTimeout)
        con.put("external_ip", externalIp.orNull)
        con.put("initiator", initiator)
        con.put("local_ep_group_id", localEpGroupId.map(_.toString).orNull)
        con.put("local_ip_vers", localIpVers)
        con.put("mtu", mtu)
        con.put("name", name.getOrElse(s"ipsec-con-$id"))
        con.put("peer_address", peerAddress)
        con.put("peer_ep_group_id", peerEpGroupId.map(_.toString).orNull)
        con.put("peer_id", peerId)
        con.put("psk", psk)
        con.put("route_mode", routeMode)
        con.put("status", status)
        con.put("tenant_id", tenantId.orNull)
        con
    }

    protected def ikePolicyJson(authAlgorithm: String = "sha1",
                                description: Option[String] = None,
                                encryptionAlgorithm: String = "aes-128",
                                id: UUID = UUID.randomUUID(),
                                ikeVersion: String = "v1",
                                lifetimeUnits: String = "seconds",
                                lifetimeValue: Int = 3600,
                                name: Option[String] = None,
                                pfs: String = "group5",
                                phase1NegotiationMode: String = "main",
                                tenantId: Option[String] = None): JsonNode = {
        val p = nodeFactory.objectNode
        p.put("auth_algorithm", authAlgorithm)
        p.put("description", description.getOrElse(s"IkePolicy with ID $id"))
        p.put("encryption_algorithm", encryptionAlgorithm)
        p.put("id", id.toString)
        p.put("ike_version", ikeVersion)
        p.put("lifetime_units", lifetimeUnits)
        p.put("lifetime_value", lifetimeValue)
        p.put("name", name.getOrElse(s"IkePolicy-$id"))
        p.put("pfs", pfs)
        p.put("phase1_negotiation_mode", phase1NegotiationMode)
        p.put("tenant_id", tenantId.orNull)
        p
    }

    protected def ipSecPolicyJson(authAlgorithm: String = "sha1",
                                  description: Option[String] = None,
                                  encapsulationMode: String = "tunnel",
                                  encryptionAlgorithm: String = "aes-128",
                                  id: UUID = UUID.randomUUID(),
                                  lifetimeUnits: String = "seconds",
                                  lifetimeValue: Int = 3600,
                                  name: Option[String] = None,
                                  pfs: String = "group5",
                                  tenantId: Option[String] = None,
                                  transformProtocol: String = "esp")
    : JsonNode = {
        val p = nodeFactory.objectNode
        p.put("auth_algorithm", authAlgorithm)
        p.put("description", description.getOrElse(s"IpSecPolicy with ID $id"))
        p.put("encapsulation_mode", encapsulationMode)
        p.put("encryption_algoritm", encryptionAlgorithm)
        p.put("id", id.toString)
        p.put("lifetime_units", lifetimeUnits)
        p.put("lifetime_value", lifetimeValue)
        p.put("name", name.getOrElse(s"IkePolicy-$id"))
        p.put("pfs", pfs)
        p.put("tenant_id", tenantId.orNull)
        p.put("transform_protocol", transformProtocol)
        p
    }

    protected def tapServiceJson(id: UUID = UUID.randomUUID(),
                                 tenantId: String = "tenant",
                                 name: String = "name",
                                 description: String = "description",
                                 portId: UUID): JsonNode = {
        val f = nodeFactory.objectNode()
        f.put("id", id.toString)
        f.put("tenant_id", tenantId)
        f.put("name", name)
        f.put("description", description)
        f.put("port_id", portId.toString)
        f
    }

    protected def tapFlowJson(id: UUID = UUID.randomUUID(),
                              tenantId: String = "tenant",
                              name: String = "name",
                              description: String = "description",
                              tapServiceId: UUID,
                              sourcePort: UUID,
                              direction: String = "BOTH"): JsonNode = {
        val f = nodeFactory.objectNode()
        f.put("id", id.toString)
        f.put("tenant_id", tenantId)
        f.put("name", name)
        f.put("description", description)
        f.put("tap_service_id", tapServiceId.toString)
        f.put("source_port", sourcePort.toString)
        f.put("direction", direction)
        f
    }

    protected case class ChainPair(inChain: Chain, outChain: Chain)
    protected def getChains(inChainId: Commons.UUID,
                            outChainId: Commons.UUID): ChainPair = {
        val fs = storage.getAll(classOf[Chain], List(inChainId, outChainId))
        val chains = fs.await()
        ChainPair(chains(0), chains(1))
    }

    protected def getChains(ipg: IPAddrGroup): ChainPair =
        getChains(ipg.getInboundChainId, ipg.getOutboundChainId)

    protected def checkPortBinding(hostId: UUID, portId: UUID,
                                   interfaceName: String): Unit = {
        val hostFtr = storage.get(classOf[Host], hostId)
        val portFtr = storage.get(classOf[Port], portId)
        val (host, port) = (hostFtr.await(), portFtr.await())
        host.getPortIdsList.asScala.map(_.asJava) should contain only portId
        port.getHostId.asJava shouldBe hostId
        port.getInterfaceName shouldBe interfaceName
    }

    protected def createHost(hostId: UUID = null): Host = {
        val id = if (hostId != null) hostId else UUID.randomUUID()
        val host = Host.newBuilder.setId(id).setName(id.toString).build()
        storage.create(host)
        host
    }

    protected def deleteHost(hostId: UUID): Unit = {
        storage.delete(classOf[Host], hostId)
    }

    protected def createTenantNetwork(taskId: Int,
                                      nwId: UUID = UUID.randomUUID(),
                                      external: Boolean = false,
                                      uplink: Boolean = false): UUID = {
        val json = networkJson(nwId, name = "tenant-network-" + nwId,
                               tenantId = "tenant", external = external,
                               uplink = uplink)
        insertCreateTask(taskId, NetworkType, json, nwId)
        nwId
    }

    protected def createUplinkNetwork(taskId: Int,
                                      nwId: UUID = UUID.randomUUID()): UUID = {
        val json = networkJson(nwId, name = "uplink-network-" + nwId,
                               uplink = true)
        insertCreateTask(taskId, NetworkType, json, nwId)
        nwId
    }

    protected def createRouter(taskId: Int, routerId: UUID = UUID.randomUUID(),
                               gwPortId: UUID = null,
                               enableSnat: Boolean = false): UUID = {
        val json = routerJson(routerId, name = "router-" + routerId,
                              gwPortId = gwPortId, enableSnat = enableSnat)
        insertCreateTask(taskId, RouterType, json, routerId)
        routerId
    }

    protected def createFirewall(taskId: Int, fwId: UUID = UUID.randomUUID(),
                                 adminStateUp: Boolean = true,
                                 firewallRuleList: List[JsonNode] = List(),
                                 addRouterIds: List[UUID] = List(),
                                 delRouterIds: List[UUID] = List()) = {
        val json = firewallJson(fwId, adminStateUp = adminStateUp,
                                firewallRuleList=firewallRuleList,
                                addRouterIds = addRouterIds,
                                delRouterIds = delRouterIds)
        insertCreateTask(taskId, FirewallType, json, fwId)
        fwId
    }

    protected def createSubnet(taskId: Int, networkId: UUID, cidr: String,
                               subnetId: UUID = UUID.randomUUID(),
                               gatewayIp: String = null,
                               dnsServers: List[String] = null,
                               hostRoutes: List[HostRoute] = null,
                               ipVersion: Int = 4): UUID = {
        val json = subnetJson(subnetId, networkId, cidr = cidr,
                              gatewayIp = gatewayIp,
                              dnsNameservers = dnsServers,
                              hostRoutes = hostRoutes,
                              ipVersion = ipVersion)
        insertCreateTask(taskId, SubnetType, json, subnetId)
        subnetId
    }

    protected def createDhcpPort(taskId: Int, networkId: UUID, subnetId: UUID,
                                 ipAddr: String,
                                 portId: UUID = UUID.randomUUID()): UUID = {
        val json = portJson(portId, networkId, deviceOwner = DeviceOwner.DHCP,
                            fixedIps = List(IPAlloc(ipAddr, subnetId)))
        insertCreateTask(taskId, PortType, json, portId)
        portId
    }

    protected def createVifPort(taskId: Int, nwId: UUID,
                                fixedIps: Seq[IPAlloc] = Seq(),
                                id: UUID = UUID.randomUUID(),
                                mac: String = MAC.random().toString,
                                sgs: Seq[UUID] = Seq(),
                                securityEnabled: Boolean = true,
                                extraDhcpOpts: List[ExtraDhcpOpts] = null)
    : UUID = {

        val json = portJson(id, nwId, macAddr = mac, fixedIps = fixedIps,
                            deviceOwner = DeviceOwner.COMPUTE,
                            securityGroups = sgs,
                            portSecurityEnabled = securityEnabled,
                            extraOpt = extraDhcpOpts)
        insertCreateTask(taskId, PortType, json, id)
        id
    }

    protected def createRouterGatewayPort(taskId: Int, networkId: UUID,
                                          gwIpAddr: String, macAddr: String,
                                          subnetId: UUID,
                                          id: UUID = UUID.randomUUID())
    : UUID = {
        val json = portJson(id, networkId, macAddr = macAddr,
                            fixedIps = Seq(IPAlloc(gwIpAddr, subnetId)),
                            deviceOwner = DeviceOwner.ROUTER_GATEWAY)
        insertCreateTask(taskId, PortType, json, id)
        id
    }

    protected def createRouterGatewayPort(taskId: Int, networkId: UUID,
                                          fixedIps: Seq[IPAlloc], macAddr: String,
                                          id: UUID)
    : UUID = {
        val json = portJson(id, networkId, macAddr = macAddr,
                            fixedIps = fixedIps,
                            deviceOwner = DeviceOwner.ROUTER_GATEWAY)
        insertCreateTask(taskId, PortType, json, id)
        id
    }

    protected def createRouterInterfacePort(taskId: Int, nwId: UUID,
                                            subnetId: UUID, rtrId: UUID,
                                            ipAddr: String,
                                            macAddr: String = MAC.random().toString,
                                            id: UUID = UUID.randomUUID(),
                                            hostId: UUID = null,
                                            ifName: String = null): UUID = {
        val json = portJson(id, nwId, hostId = hostId, ifName = ifName,
                            deviceOwner = DeviceOwner.ROUTER_INTERFACE,
                            deviceId = rtrId, macAddr = macAddr,
                            fixedIps = List(IPAlloc(ipAddr, subnetId)))
        insertCreateTask(taskId, PortType, json, id)
        id
    }

    protected def createRouterInterface(taskId: Int, routerId: UUID,
                                        portId: UUID, subnetId: UUID): Unit = {
        val json = routerInterfaceJson(routerId, portId, subnetId)
        insertCreateTask(taskId, NeutronResourceType.RouterInterface,
                         json, routerId)
    }

    protected def createSecurityGroup(taskId: Int,
                                      sgId: UUID = UUID.randomUUID(),
                                      name: String = null, desc: String = null,
                                      tenantId: String = null,
                                      rules: Seq[JsonNode] = Seq()): UUID = {
        val json = sgJson(sgId, name, desc, tenantId, rules)
        insertCreateTask(taskId, NeutronResourceType.SecurityGroup, json, sgId)
        sgId
    }

    protected def bgpSpeakerJson(logicalRouterId: UUID,
                                 id: UUID = UUID.randomUUID(),
                                 tenantId: String = "admin",
                                 name: String = null,
                                 localAs: Int = 12345,
                                 ipVersion: Int = 4,
                                 delBgpPeerIds: Seq[UUID] = Seq())
    : JsonNode = {
        val p = nodeFactory.objectNode
        p.put("id", id.toString)
        p.put("tenant_id", tenantId.toString)
        p.put("name", name)
        p.put("local_as", localAs)
        p.put("ip_version", ipVersion)
        p.put("logical_router", logicalRouterId.toString)
        val delPeersNode = p.putArray("del_bgp_peer_ids")
        for (peerId <- delBgpPeerIds) delPeersNode.add(peerId.toString)
        p
    }

    protected def bgpPeerJson(peerIp: String,
                              bgpSpeaker: JsonNode,
                              id: UUID = UUID.randomUUID(),
                              tenantId: String = "admin",
                              name: String = null,
                              remoteAs: Int = 23456,
                              authType: AuthType = AuthType.MD5,
                              password: String = "password"): JsonNode = {
        val p = nodeFactory.objectNode
        p.put("id", id.toString)
        p.put("tenant_id", tenantId.toString)
        p.put("name", name)
        p.put("remote_as", remoteAs)
        p.put("peer_ip", peerIp)
        p.put("auth_type", authType.toString)
        p.put("password", password)
        p.set("bgp_speaker", bgpSpeaker)
        p
    }

    protected def createBgpPeer(taskId: Int,
                                routerId: UUID,
                                peerIp: String,
                                id: UUID = UUID.randomUUID(),
                                tenantId: String = "admin",
                                name: String = "bgp_peer",
                                remoteAs: Int = 12345,
                                authType: AuthType = AuthType.MD5,
                                password: String = "password",
                                speakerId: UUID = UUID.randomUUID(),
                                speakerName: String = "bgp_speaker",
                                speakerLocalAs: Int = 23456,
                                ipVersion: Int = 4
                                ): UUID = {
        val speakerJson = bgpSpeakerJson(routerId, speakerId, tenantId,
                                         speakerName, speakerLocalAs,
                                         ipVersion)
        val json = bgpPeerJson(peerIp, speakerJson, id, tenantId, name,
                               remoteAs, authType, password)
        insertCreateTask(taskId, BgpPeerType, json, id)
        id
    }
}

@RunWith(classOf[JUnitRunner])
class C3POMinionTest extends C3POMinionTestBase {

    "C3PO" should "execute VIF port CRUD tasks" in {
        // Creates Network 1.
        val network1Uuid = UUID.randomUUID()
        val network1Json = networkJson(network1Uuid, "tenant1", "private-net")
        insertCreateTask(2, NetworkType, network1Json, network1Uuid)

        val vifPortUuid = UUID.randomUUID()
        val vifPortId = toProto(vifPortUuid)
        eventually {
            storage.exists(classOf[Port], vifPortId).await() shouldBe false
        }

        // Creates a VIF port.
        val portMac = MAC.random()
        val vifPortJson = portJson(name = "port1", id = vifPortUuid,
                                   networkId = network1Uuid,
                                   macAddr = portMac.toString)
        insertCreateTask(3, PortType, vifPortJson, vifPortUuid)

        val vifPort = eventually(storage.get(classOf[Port], vifPortId).await())
        vifPort.getId should be (vifPortId)
        vifPort.getNetworkId should be (toProto(network1Uuid))
        vifPort.getAdminStateUp shouldBe true

        val network1 = storage.get(classOf[Network], network1Uuid).await()
        network1.getPortIdsList should contain (vifPortId)
        directory.exists(stateTableStorage.bridgeMacEntryPath(
            network1Uuid, 0, portMac, vifPortUuid)) shouldBe true

        // Update the port admin status and MAC address. Through the Neutron
        // API, you cannot change the Network the port is attached to.
        val portMac2 = MAC.random()
        val vifPortUpdate = portJson(id = vifPortUuid, networkId = network1Uuid,
                                     adminStateUp = false,      // Down now.
                                     macAddr = portMac2.toString)
        insertUpdateTask(4, PortType, vifPortUpdate, vifPortUuid)
        val updatedVifPort = storage.get(classOf[Port], vifPortId).await()
        updatedVifPort.getAdminStateUp shouldBe false
        directory.exists(
            stateTableStorage.bridgeMacEntryPath(network1Uuid, 0, portMac,
                                                 vifPortUuid)) shouldBe false
        directory.exists(
            stateTableStorage.bridgeMacEntryPath(network1Uuid, 0, portMac2,
                                                 vifPortUuid)) shouldBe true

        // Delete the VIF port.
        insertDeleteTask(5, PortType, vifPortUuid)

        storage.exists(classOf[Port], vifPortId).await() shouldBe false
        directory.exists(
            stateTableStorage.bridgeMacEntryPath(network1Uuid, 0, portMac2,
                                                 vifPortUuid)) shouldBe false

        // Back reference was cleared.
        val finalNw1 = storage.get(classOf[Network], network1Uuid).await()
        finalNw1.getPortIdsList should not contain vifPortId
        // You can delete the Network1 now.
        storage.delete(classOf[Network], network1Uuid)
    }



    it should "handle Subnet CRUD" in {
        val nId = UUID.randomUUID()
        val nJson = networkJson(nId, "net tenant")

        // Create a subnet
        val sId = UUID.randomUUID()
        val cidr = IPv4Subnet.fromCidr("10.0.0.0/24")
        val gatewayIp = "10.0.0.1"
        val nameServers = List("8.8.8.8")
        val hrDest = "10.0.0.0/24"
        val hrNexthop = "10.0.0.27"
        val hostRoutes = List(HostRoute(hrDest, hrNexthop))
        val sJson = subnetJson(sId, nId, name = "test sub",
                               cidr = cidr.toString, gatewayIp = gatewayIp,
                               dnsNameservers = nameServers,
                               hostRoutes = hostRoutes)
        insertCreateTask(2, NetworkType, nJson, nId)
        insertCreateTask(3, SubnetType, sJson, sId)

        // Verify the created subnet
        val dhcp = eventually(storage.get(classOf[Dhcp], sId).await())
        dhcp should not be null
        dhcp.getDefaultGateway.getAddress should be(gatewayIp)
        dhcp.getEnabled shouldBe true
        dhcp.getSubnetAddress.getAddress should be(cidr.getAddress.toString)
        dhcp.getSubnetAddress.getPrefixLength should be(cidr.getPrefixLen)
        dhcp.getServerAddress.getAddress should be(gatewayIp)
        dhcp.getDnsServerAddressCount shouldBe 1
        dhcp.getDnsServerAddress(0) shouldBe
            IPAddressUtil.toProto(nameServers(0))
        dhcp.getOpt121RoutesCount shouldBe 1
        dhcp.getOpt121Routes(0).getDstSubnet shouldBe
            IPSubnetUtil.toProto(hrDest)
        dhcp.getOpt121Routes(0).getGateway shouldBe
            IPAddressUtil.toProto(hrNexthop)

        // Create a DHCP port to verify that the metadata opt121 route
        val portId = UUID.randomUUID()
        val dhcpPortIp = "10.0.0.7"
        val pJson = portJson(id = portId, networkId = nId,
            adminStateUp = true, deviceOwner = DeviceOwner.DHCP,
            fixedIps = List(IPAlloc(dhcpPortIp, sId)))
        insertCreateTask(4, PortType, pJson, portId)

        // Update the subnet
        val cidr2 = IPv4Subnet.fromCidr("10.0.1.0/24")
        val gatewayIp2 = "10.0.1.1"
        val dnss = List("8.8.4.4")
        val sJson2 = subnetJson(sId, nId, name = "test sub2",
                                cidr = cidr2.toString, gatewayIp = gatewayIp2,
                                dnsNameservers = dnss)
        insertUpdateTask(5, SubnetType, sJson2, sId)

        // Verify the updated subnet
        eventually {
            val dhcp2 = storage.get(classOf[Dhcp], sId).await()
            dhcp2 should not be null
            dhcp2.getDefaultGateway.getAddress shouldBe gatewayIp2
            dhcp2.getEnabled shouldBe true
            dhcp2.getSubnetAddress.getAddress shouldBe cidr2.getAddress.toString
            dhcp2.getSubnetAddress.getPrefixLength shouldBe cidr2.getPrefixLen
            dhcp2.getServerAddress.getAddress shouldBe dhcpPortIp
            dhcp2.getDnsServerAddressCount shouldBe 1
            dhcp2.getDnsServerAddress(0) shouldBe IPAddressUtil.toProto(dnss(0))
            dhcp2.getOpt121RoutesCount shouldBe 1
            dhcp2.getOpt121Routes(0).getGateway shouldBe
            IPAddressUtil.toProto(dhcpPortIp)
        }

        // Delete the subnet
        insertDeleteTask(6, SubnetType, sId)

        // Verify deletion
        eventually {
            storage.getAll(classOf[Dhcp]).await().size shouldBe 0
        }

        // Delete the DHCP Port, whose fixed IP points to the deleted subnet.
        insertDeleteTask(7, PortType, portId)
        eventually {
            storage.exists(classOf[Port], portId).await() shouldBe false
        }
    }

    it should "handle Config / AgentMembership Create" in {
        val cId = UUID.randomUUID()
        val cJson = configJson(cId, TunnelProtocol.VXLAN)
        insertCreateTask(2, ConfigType, cJson, cId)

        // Verify the created default tunnel zone
        val tz = eventually(storage.get(classOf[TunnelZone], cId).await())
        tz.getId shouldBe toProto(cId)
        tz.getType shouldBe TunnelZone.Type.VXLAN
        tz.getName shouldBe "DEFAULT"

        // Set up the host.
        val hostId = UUID.randomUUID()
        val host = Host.newBuilder.setId(hostId).build()
        storage.create(host)

        val ipAddress = "192.168.0.1"
        val amJson = agentMembershipJson(hostId, ipAddress)
        insertCreateTask(3, AgentMembershipType, amJson, hostId)

        eventually {
            val tz1 = storage.get(classOf[TunnelZone], cId).await()
            tz1.getHostsCount shouldBe 1
            tz1.getHosts(0).getHostId shouldBe toProto(hostId)
            tz1.getHosts(0).getIp shouldBe IPAddressUtil.toProto(ipAddress)
        }

        // Tests that the host's reference to the tunnel zone is updated.
        val hostWithTz = storage.get(classOf[Host], hostId).await()
        hostWithTz.getTunnelZoneIdsCount shouldBe 1
        hostWithTz.getTunnelZoneIds(0) shouldBe toProto(cId)

        insertDeleteTask(4, AgentMembershipType, hostId)
        eventually {
            val tz2 = storage.get(classOf[TunnelZone], cId).await()
            tz2.getHostsList.size shouldBe 0
        }

        // Tests that the host's reference to the tunnel zone is cleared.
        val hostNoTz = storage.get(classOf[Host], hostId).await()
        hostNoTz.getTunnelZoneIdsCount shouldBe 0
    }

    it should "treat a delete operation as no-op if the Neutron object " +
              "doesn't exist in the data store" in {
        // Create a network.
        val nwId = createTenantNetwork(10)
        eventually(
            storage.exists(classOf[NeutronNetwork], nwId).await() shouldBe true)

        // Delete it.
        insertDeleteTask(20, NetworkType, nwId)
        eventually {
            storage.exists(classOf[NeutronNetwork], nwId).await() shouldBe false
        }

        // Try to delete it again. The translator should treat this as a no-op
        // because the Neutron object no longer exists.
        insertDeleteTask(30, NetworkType, nwId)

        // C3PO won't proceed to the next task until the current one succeeds,
        // so we can test the success of the last step by inserting a create
        // task and waiting for it to succeed.
        val rtrId = createRouter(40)
        eventually(
            storage.exists(classOf[NeutronRouter], rtrId).await() shouldBe true)
    }
}
