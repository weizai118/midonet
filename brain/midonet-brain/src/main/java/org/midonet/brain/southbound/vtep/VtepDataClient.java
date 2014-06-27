/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.List;

import org.apache.commons.lang3.tuple.Pair;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.plugin.Connection;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;

import rx.Observable;

import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.McastMac;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

/**
 * Represents a connection to a VTEP-enabled switch.
 */
public interface VtepDataClient {

    /**
     * @return the management ip that this client connects to.
     */
    public IPv4Addr getManagementIp();

    /**
     * @return the tunnel ip of this VTEP
     */
    public IPv4Addr getTunnelIp();

    /**
     * @return the management UDP port where this client connects to.
     */
    public int getManagementPort();

    /**
     * Lists all physical switches configured in the VTEP.
     *
     * @return the physical switches.
     */
    public List<PhysicalSwitch> listPhysicalSwitches();

    /**
     * Lists all logical switches configured in the VTEP.
     *
     * @return the logical switches.
     */
    public List<LogicalSwitch> listLogicalSwitches();

    /**
     * Lists all the physical ports in a given physical switch.
     *
     * @param psUuid uuid of the physical switch
     * @return the list of physical ports
     */
    public List<PhysicalPort> listPhysicalPorts(
        org.opendaylight.ovsdb.lib.notation.UUID psUuid);

    /**
     * Lists all the multicast macs local to the VTEP.
     */
    public List<McastMac> listMcastMacsLocal();

    /**
     * Lists all the multicast macs remote to the VTEP.
     */
    public List<McastMac> listMcastMacsRemote();

    /**
     * Lists all the unicast macs local to the VTEP.
     */
    public List<UcastMac> listUcastMacsLocal();

    /**
     * Lists all the unicast macs remote to the VTEP.
     */
    public List<UcastMac> listUcastMacsRemote();

    /**
     * Retrieve a LogicalSwitch by UUID.
     */
    public LogicalSwitch getLogicalSwitch(UUID id);

    /**
     * Retrieve a LogicalSwitch by name.
     */
    public LogicalSwitch getLogicalSwitch(String name);

    /**
     * Connect to the VTEP database instance:
     *
     * @param mgmtIp the management ip of the VTEP
     * @param port the management port of the VTEP
     */
    public void connect(IPv4Addr mgmtIp, int port);

    /**
     * Disconnect from the VTEP database instance:
     */
    public void disconnect();

    /**
     * Adds a new logical switch to the remote VTEP instance, using the
     * given VNI as tunnel key.
     *
     * @param name the name of the new logical switch
     * @param vni the VNI associated to the new logical switch
     * @return operation result status
     */
    public StatusWithUuid addLogicalSwitch(String name, int vni);

    /**
     * Binds a physical port and vlan to the given logical switch.
     *
     * @param lsName of the logical switch
     * @param portName the physical port in the physical switch
     * @param vlan vlan tag to match for traffic on the given phys. port
     * @param vni vni to use if the logical switch does not exist
     * @param floodIps ips of the vtep peers that will get a remote Mcast and
     *                 Ucast entry for unknown-dst.
     *
     * @return operation result status
     */
    public Status bindVlan(String lsName, String portName, int vlan,
                           Integer vni, List<IPv4Addr> floodIps);

    /**
     * Binds a list of (physical_port, vlan) to a given logical switch.
     *
     * @param lsUuid id of the logical switch, it must exist
     * @param portVlanPairs the pairs of physical port and vlans to bind
     *
     * @return operation result status
     */
    public Status addBindings(UUID lsUuid,
                              List<Pair<String, Integer>> portVlanPairs);

    /**
     * Adds a new entry to the Ucast_Macs_Remote table.
     *
     * @param lsName of the logical switch where mac is to be added
     * @param mac the mac address
     * @param ip the ip associated to the mac (for ARP) - can be null.
     * @param tunnelEndPoint the ip of the vxlan tunnel peer where packets
     *                       addressed to mac should be tunnelled to
     * @return the result of the operation
     */
    public Status addUcastMacRemote(String lsName, MAC mac, IPv4Addr ip,
                                    IPv4Addr tunnelEndPoint );

    /**
     * Adds a new entry to the Mcast_Macs_Remote table.
     *
     * @param lsName of the logical switch where mac is to be added
     * @param vMac the mac address
     * @param tunnelEndpoint the ip of the vxlan tunnel peer where packets
     *                       addressed to mac should be tunnelled to
     * @return the result of the operation
     */
    public Status addMcastMacRemote(String lsName, VtepMAC vMac,
                                    IPv4Addr tunnelEndpoint);

    /**
     * Deletes all entries from the Ucast_Mac_Remote table that matches the
     * specified MAC address, logical switch name and IP address. The method
     * returns a NotFound status when there is no matching entry.
     *
     * @param lsName The logical switch name
     * @param mac The MAC address
     * @param macIp the IP in the entry to remove (nullable)
     * @return Operation result status
     */
    public Status delUcastMacRemote(String lsName, MAC mac, IPv4Addr macIp);

    /**
     * Deletes all entries from the Ucast_Mac_Remote table that match the
     * specified MAC address and logical switch name. The method returns
     * a NotFound status when there is no matching entry.
     *
     * @param lsName The logical switch name
     * @param mac The MAC address
     * @return Operation result status
     */
    public Status delUcastMacRemoteAllIps(String lsName, MAC mac);

    /**
     * Deletes all entries from the Mcast_Mac_Remote table that match the
     * specified MAC address and logical switch name. The method returns a
     * NotFound status when there is no matching entry.
     *
     * @param lsName The logical switch name
     * @param vMac The MAC address
     * @return Operation result status
     */
    public Status delMcastMacRemoteAllIps(String lsName, VtepMAC vMac);

    /**
     * Provides an observable notifying when a connection is established to a
     * VTEP.
     */
    public Observable<Connection> connectObservable();

    /**
     * Provides an observable notifying when a connection is disconnected from
     * a VTEP.
     */
    public Observable<Connection> disconnectObservable();

    /**
     * Provides an observable producing a stream of updates from the VTEP.
     */
    public Observable<TableUpdates> updatesObservable();

    /**
     * Deletes a binding between the given port and vlan.
     *
     * @param portName of the physical port
     * @param vlanId the vlan id
     * @return the result of the operation
     */
    public Status deleteBinding(String portName, int vlanId);

    /**
     * Deletes the logical switch, with all its bindings and associated
     * ucast / mcast remote macs.
     * @param name of the logical switch
     * @return the result of the operation
     */
    public Status deleteLogicalSwitch(String name);

    /**
     * Returns the full list of port VLAN pairs on the given logical switch.
     *
     * @param lsId the logical switch UUID
     * @return the list of (port_uuid, vlan) representing bindings on this
     * logical switch
     */
    public List<Pair<UUID, Integer>> listPortVlanBindings(UUID lsId);

    /**
     * Clears all bindings of the given logical switch in a single transaction.
     *
     * @param lsUuid the logical switch UUID
     * @return the result of the operation
     */
    public Status clearBindings(UUID lsUuid);

}
