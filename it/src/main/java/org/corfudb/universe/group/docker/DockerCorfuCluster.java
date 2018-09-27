package org.corfudb.universe.group.docker;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.spotify.docker.client.DockerClient;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.runtime.BootstrapUtil;
import org.corfudb.runtime.view.Layout;
import org.corfudb.runtime.view.Layout.LayoutSegment;
import org.corfudb.runtime.view.Layout.LayoutStripe;
import org.corfudb.universe.group.CorfuCluster;
import org.corfudb.universe.group.Group;
import org.corfudb.universe.node.CorfuServer;
import org.corfudb.universe.node.LocalCorfuClient;
import org.corfudb.universe.node.Node;
import org.corfudb.universe.node.Node.NodeParams;
import org.corfudb.universe.node.docker.CorfuServerDockerized;
import org.corfudb.universe.util.ClassUtils;

import java.io.File;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static lombok.Builder.Default;
import static org.corfudb.universe.node.CorfuServer.ServerParams;
import static org.corfudb.universe.universe.Universe.UniverseParams;

/**
 * Provides Docker implementation of {@link Group}.
 */
@Slf4j
public class DockerCorfuCluster implements CorfuCluster {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss");
    private static final String CORFU_DB_PATH = "corfudb";

    @Default
    private final ConcurrentMap<String, Node> nodes = new ConcurrentHashMap<>();
    private final DockerClient docker;
    @Getter
    private final CorfuClusterParams params;
    private final UniverseParams universeParams;

    private final File serverLogDir;

    @Builder
    public DockerCorfuCluster(DockerClient docker, CorfuClusterParams params, UniverseParams universeParams) {
        this.docker = docker;
        this.params = params;
        this.universeParams = universeParams;

        String clusterLogPath = params.getName() + "_" + LocalDateTime.now().format(DATE_FORMATTER);
        this.serverLogDir = Paths.get(universeParams.getBaseDir(), CORFU_DB_PATH, clusterLogPath).toFile();
    }

    @Override
    public DockerCorfuCluster deploy() {
        if (!serverLogDir.exists() && serverLogDir.mkdirs()) {
            log.info("Created new cluster log directory at {}.", serverLogDir);
        }

        for (ServerParams nodeParams : params.getNodesParams()) {
            deployNode(nodeParams);
        }

        bootstrap();

        return this;
    }

    private Node deployNode(ServerParams nodeParams) {
        CorfuServer node = CorfuServerDockerized.builder()
                .universeParams(universeParams)
                .params(ClassUtils.cast(nodeParams, ServerParams.class))
                .docker(docker)
                .serverLogDir(serverLogDir)
                .build();

        node.deploy();
        nodes.put(node.getParams().getName(), node);
        return node;
    }

    @Override
    public void stop(Duration timeout) {
        nodes.values().forEach(node -> {
            try {
                node.stop(timeout);
            } catch (Exception e) {
                log.warn("Can't stop node: {} in group: {}", node.getParams().getName(), getParams().getName(), e);
            }
        });
    }

    @Override
    public void kill() {
        nodes.values().forEach(node -> {
            try {
                node.kill();
            } catch (Exception e) {
                log.warn("Can't kill node: {} in group: {}", node.getParams().getName(), getParams().getName(), e);
            }
        });
    }

    @Override
    public void destroy() {
        nodes.values().forEach(node -> {
            try {
                node.destroy();
            } catch (Exception e) {
                log.warn("Can't destroy node: {} in group: {}", node.getParams().getName(), getParams().getName(), e);
            }
        });
    }

    @Override
    public Node add(NodeParams nodeParams) {
        ServerParams serverParams = ClassUtils.cast(nodeParams);
        Node node = deployNode(serverParams);
        params.add(serverParams);
        return node;
    }

    @Override
    public <T extends Node> T getNode(String nodeName) {
        return ClassUtils.cast(nodes.get(nodeName));
    }

    @Override
    public <T extends Node> ImmutableMap<String, T> nodes() {
        return ClassUtils.cast(ImmutableMap.copyOf(nodes));
    }

    @Override
    public void bootstrap() {
        BootstrapUtil.bootstrap(getLayout(), params.getBootStrapRetries(), params.getRetryTimeout());
    }


    private Layout getLayout() {
        long epoch = 0;
        UUID clusterId = UUID.randomUUID();
        List<String> servers = params.getServers();

        LayoutSegment segment = new LayoutSegment(
                Layout.ReplicationMode.CHAIN_REPLICATION,
                0L,
                -1L,
                Collections.singletonList(new LayoutStripe(params.getServers()))
        );
        return new Layout(servers, servers, Collections.singletonList(segment), epoch, clusterId);
    }

    @Override
    public LocalCorfuClient getLocalCorfuClient(ImmutableList<String> layoutServers) {
        return LocalCorfuClient.builder()
                .serverEndpoints(layoutServers)
                .build()
                .deploy();
    }
}
