/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.client.dto;

import java.net.URI;

public class DtoVxLanPort extends DtoBridgePort {

    private String mgmtIpAddr;
    private int mgmtPort;
    private int vni;
    private URI bindings;

    @Override
    public Short getVlanId() {
        return null;
    }

    @Override
    public String getType() {
        return PortType.VXLAN;
    }

    public String getMgmtIpAddr() {
        return mgmtIpAddr;
    }

    public void setMgmtIpAddr(String mgmtIpAddr) {
        this.mgmtIpAddr = mgmtIpAddr;
    }

    public int getMgmtPort() {
        return mgmtPort;
    }

    public void setMgmtPort(int mgmtPort) {
        this.mgmtPort = mgmtPort;
    }

    public int getVni() {
        return vni;
    }

    public void setVni(int vni) {
        this.vni = vni;
    }

    public URI getBindings() {
        return bindings;
    }

    public void setBindings(URI bindings) {
        this.bindings = bindings;
    }

}
