package org.corfudb.universe.node.docker;

import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.PortBinding;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.corfudb.universe.node.CorfuServer;
import org.corfudb.universe.node.NodeException;
import org.corfudb.universe.universe.UniverseException;

import java.io.FileReader;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.corfudb.universe.universe.Universe.UniverseParams;

/**
 * Implements a docker instance representing a {@link CorfuServer}.
 */
@Slf4j
@Builder
public class CorfuServerDockerized implements CorfuServer {
    private static final String IMAGE_NAME = "corfu-server:" + getAppVersion();
    public static final String ALL_NETWORK_INTERFACES = "0.0.0.0";

    @Getter
    private final ServerParams params;
    private final DockerClient docker;
    private final UniverseParams universeParams;

    /**
     * Deploys a Corfu server / docker container
     */
    @Override
    public CorfuServerDockerized deploy() {
        log.info("Deploying the Corfu server. {}", params.getName());

        deployContainer();

        return this;
    }

    /**
     * This method attempts to gracefully stop the Corfu server. After timeout, it will kill the Corfu server.
     *
     * @param timeout a duration after which the stop will kill the server
     * @throws NodeException this exception will be thrown if the server cannot be stopped.
     */
    @Override
    public void stop(Duration timeout) {
        log.info("Stopping the Corfu server. {}", params.getName());

        try {
            ContainerInfo container = docker.inspectContainer(params.getName());
            if (!container.state().running()) {
                log.debug("The container `{}` is already stopped", container.name());
                return;
            }
            docker.stopContainer(params.getName(), (int) timeout.getSeconds());
        } catch (DockerException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeException("Can't stop Corfu server", e);
        }
    }

    /**
     * Immediately kill the Corfu server.
     *
     * @throws NodeException this exception will be thrown if the server can not be killed.
     */
    @Override
    public void kill() {
        log.info("Killing the Corfu server. {}", params.getName());

        try {
            ContainerInfo container = docker.inspectContainer(params.getName());
            if (!container.state().running()) {
                log.debug("The container `{}` is not running", container.name());
                return;
            }
            docker.killContainer(params.getName());
        } catch (DockerException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new NodeException("Can't kill Corfu server " + params.getName(), ex);
        }
    }

    /**
     * Deploy and start docker container, expose ports, connect to a network
     *
     * @return docker container id
     */
    private String deployContainer() {
        ContainerConfig containerConfig = buildContainerConfig();

        String id;
        try {
            ContainerCreation container = docker.createContainer(containerConfig, params.getName());
            id = container.id();

            addShutdownHook();

            docker.connectToNetwork(id, docker.inspectNetwork(universeParams.getNetworkName()).id());

            docker.startContainer(id);
        } catch (InterruptedException | DockerException e) {
            Thread.currentThread().interrupt();
            throw new NodeException("Can't start a container", e);
        }

        return id;
    }

    private ContainerConfig buildContainerConfig() {
        // Bind ports
        String[] ports = {String.valueOf(params.getPort())};
        Map<String, List<PortBinding>> portBindings = new HashMap<>();
        for (String port : ports) {
            List<PortBinding> hostPorts = new ArrayList<>();
            hostPorts.add(PortBinding.of(ALL_NETWORK_INTERFACES, port));
            portBindings.put(port, hostPorts);
        }

        HostConfig hostConfig = HostConfig.builder()
                .portBindings(portBindings)
                .autoRemove(true)
                .build();

        // Compose command line for starting Corfu
        String cmdLine = String.format(
                "java -cp *.jar org.corfudb.infrastructure.CorfuServer %s",
                getCommandLineParams()
        );

        return ContainerConfig.builder()
                .hostConfig(hostConfig)
                .image(IMAGE_NAME)
                .hostname(params.getName())
                .exposedPorts(ports)
                .cmd("sh", "-c", cmdLine)
                .build();
    }

    private void addShutdownHook() {
        // Just in case a test failed and didn't kill the container
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                kill();
            } catch (Exception e) {
                log.debug("Corfu server shutdown hook. Can't kill container: {}", params.getName());
            }
        }));
    }

    private static String getAppVersion() {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model;
        try {
            model = reader.read(new FileReader("pom.xml"));
            return model.getParent().getVersion();
        } catch (IOException | XmlPullParserException e) {
            throw new NodeException("Can't parse application version", e);
        }
    }

    /**
     * This method create a command line string for starting Corfu server
     *
     * @return command line parameters
     */
    private String getCommandLineParams() {
        StringBuilder cmd = new StringBuilder();
        cmd.append("-a ").append(ALL_NETWORK_INTERFACES);

        switch (params.getPersistence()) {
            case DISK:
                if (StringUtils.isEmpty(params.getLogDir())) {
                    throw new UniverseException("Invalid log dir in disk persistence mode");
                }
                cmd.append(" -l ").append(params.getLogDir());
                break;
            case MEMORY:
                cmd.append(" -m");
                break;
        }

        if (params.getMode() == Mode.SINGLE) {
            cmd.append(" -s");
        }

        cmd.append(" -d ").append(params.getLogLevel().toString()).append(" ");

        cmd.append(params.getPort());

        String cmdLineParams = cmd.toString();
        log.trace("Command line parameters: {}", cmdLineParams);

        return cmdLineParams;
    }
}
