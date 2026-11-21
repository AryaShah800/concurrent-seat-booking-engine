package com.aryashah.seatbooking.support;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.io.IOException;
import java.net.ServerSocket;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Single-node Kafka in KRaft mode (apache/kafka:3.9.0, no Zookeeper). The broker port is bound
 * to the same number on the host, so the advertised address stays valid across pause/unpause.
 */
public class SingleNodeKafka extends GenericContainer<SingleNodeKafka> {

    private final int port;

    public SingleNodeKafka() {
        super("apache/kafka:3.9.0");
        this.port = freePort();
        withEnv("KAFKA_NODE_ID", "1");
        withEnv("KAFKA_PROCESS_ROLES", "broker,controller");
        withEnv("KAFKA_LISTENERS", "PLAINTEXT://:" + port + ",CONTROLLER://:9093");
        withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + port);
        withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER");
        withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT");
        withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093");
        withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT");
        withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");
        withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1");
        withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1");
        withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");
        withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(new HostConfig().withPortBindings(
                new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(port)))));
        addExposedPort(port);
        waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1));
    }

    public String getBootstrapServers() {
        return "localhost:" + port;
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
