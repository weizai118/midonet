/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.neutron;

import com.google.inject.Inject;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.client.neutron.Neutron;
import org.midonet.client.neutron.NeutronMediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;
import java.net.URI;

@Path(NeutronUriBuilder.NEUTRON)
public class NeutronResource extends AbstractResource {

    private final static Logger log = LoggerFactory.getLogger(
            NeutronResource.class);

    private final ResourceFactory factory;

    @Inject
    public NeutronResource(RestApiConfig config, UriInfo uriInfo,
                           SecurityContext context, ResourceFactory factory) {
        super(config, uriInfo, context, null);
        this.factory = factory;
    }

    @Path(NeutronUriBuilder.NETWORKS)
    public NetworkResource getNetworkResource() {
        return factory.getNeutronNetworkResource();
    }

    @Path(NeutronUriBuilder.SUBNETS)
    public SubnetResource getSubnetResource() {
        return factory.getNeutronSubnetResource();
    }

    @Path(NeutronUriBuilder.PORTS)
    public PortResource getPortResource() {
        return factory.getNeutronPortResource();
    }

    @Path(NeutronUriBuilder.ROUTERS)
    public RouterResource getRouterResource() {
        return factory.getNeutronRouterResource();
    }

    @Path(NeutronUriBuilder.SECURITY_GROUPS)
    public SecurityGroupResource getSecurityGroupResource() {
        return factory.getNeutronSecurityGroupResource();
    }

    @Path(NeutronUriBuilder.SECURITY_GROUP_RULES)
    public SecurityGroupRuleResource getSecurityGroupRuleResource() {
        return factory.getNeutronSecurityGroupRuleResource();
    }

    /**
     * Handler to getting a neutron object.
     *
     * @return A Neutron object.
     */
    @GET
    @RolesAllowed(AuthRole.ADMIN)
    @Produces(NeutronMediaType.NEUTRON_JSON_V1)
    public Neutron get() {

        Neutron neutron = new Neutron();

        URI baseUri = getBaseUri();
        neutron.uri = NeutronUriBuilder.getNeutron(baseUri);
        neutron.networks = NeutronUriBuilder.getNetworks(baseUri);
        neutron.networkTemplate = NeutronUriBuilder.getNetworkTemplate(
                baseUri);
        neutron.subnets = NeutronUriBuilder.getSubnets(baseUri);
        neutron.subnetTemplate = NeutronUriBuilder.getSubnetTemplate(baseUri);
        neutron.ports = NeutronUriBuilder.getPorts(baseUri);
        neutron.portTemplate = NeutronUriBuilder.getPortTemplate(baseUri);
        neutron.routers = NeutronUriBuilder.getRouters(baseUri);
        neutron.routerTemplate = NeutronUriBuilder.getRouterTemplate(baseUri);
        neutron.addRouterInterfaceTemplate =
                NeutronUriBuilder.getAddRouterInterfaceTemplate(baseUri);
        neutron.removeRouterInterfaceTemplate =
                NeutronUriBuilder.getRemoveRouterInterfaceTemplate(baseUri);
        neutron.securityGroups = NeutronUriBuilder.getSecurityGroups(baseUri);
        neutron.securityGroupTemplate =
                NeutronUriBuilder.getSecurityGroupTemplate(baseUri);
        neutron.securityGroupRules =
                NeutronUriBuilder.getSecurityGroupRules(baseUri);
        neutron.securityGroupRuleTemplate =
                NeutronUriBuilder.getSecurityGroupRuleTemplate(baseUri);
        return neutron;
    }
}
