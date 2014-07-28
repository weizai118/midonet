/*
 * Copyright (c) 2013 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman;

import java.nio.file.Files;
import java.nio.file.Paths;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.host.Host;
import org.midonet.config.ConfigProvider;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.guice.cluster.ClusterClientModule;
import org.midonet.midolman.guice.config.ConfigProviderModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.ZookeeperConnectionModule;
import org.midonet.midolman.version.guice.VersionModule;

/** This static class offers a static method startCluster() which loads a
 *  minimal guice profile including the necessary midolman components to start
 *  a functional zookeeper connection and have access to an implementation
 *  of DataClient. This method takes as an input parameter a
 *  local path to a midolman configuration file and is threadsafe.
 *  It us guaranteed that the cluster is started at most once.
 *  Once the cluster has been started, a DataClient instance can
 *  be obtained by calling the static method getClusterClient(). */
public class ZkCluster {

    private ZkCluster() { }

    static final Logger log = LoggerFactory.getLogger(ZkCluster.class);

    private static Injector clusterInjector = null;

    private static Injector getInjector() {
        if (clusterInjector == null)
            throw new IllegalStateException("Cluster has not been started yet");
        return clusterInjector;
    }

    public static DataClient getClusterClient() {
        return getInjector().getInstance(DataClient.class);
    }

    synchronized public static void startCluster(String configFilePath) {
        if (clusterInjector != null)
            return;

        log.info("Injecting custom ZkCluster juice profile");
        clusterInjector = Guice.createInjector(
            new ZookeeperConnectionModule(),
            new VersionModule(),
            new ConfigProviderModule(configFilePath),
            new ClusterClientModule(),
            new SerializationModule(),
            new MidolmanConfigModule()
        );
    }

    /** Integration testing method that prints hosts currently registed in the
    *   the cluster. */
    public static void printHost(DataClient dc) {
        try {
            for (Host h: dc.hostsGetAll()) {
                log.info("found host {}", h);
            }
        } catch (Exception e) {
            log.error("error while querying hosts", e);
        }
    }

    /** Integration method that starts a minimal ZkCluster module and prints the
     *  hosts currently registered in the cluster. You can invoke this method
     *  from the command line with $ java -cp full_jar org.midonet.ZkCluster.*/
    public static void main(String[] args) {

        String configFilePath = "./midolman/conf/midolman.conf";
        if (args.length > 0)
            configFilePath = args[0];

        if (!Files.isReadable(Paths.get(configFilePath))) {
            log.error("missing or invalid config file \"{}\"", configFilePath);
            System.exit(1);
        }

        startCluster(configFilePath);

        printHost(getClusterClient());

        System.exit(0);
    }

    public static class MidolmanConfigModule extends PrivateModule {

        @Override
        protected void configure() {
            binder().requireExplicitBindings();

            requireBinding(ConfigProvider.class);

            bind(MidolmanConfig.class)
                .toProvider(MidolmanConfigProvider.class)
                .asEagerSingleton();
            expose(MidolmanConfig.class);
        }

        public static class MidolmanConfigProvider
                implements Provider<MidolmanConfig> {

            @Inject
            ConfigProvider configProvider;

            @Override
            public MidolmanConfig get() {
                return configProvider.getConfig(MidolmanConfig.class);
            }
        }

    }

}
