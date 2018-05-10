package org.corfudb.protocols.wireprotocol;

import io.netty.buffer.ByteBuf;

import lombok.Data;

import org.corfudb.util.NodeLocator;

/**
 * ServerMetrics is composed of the various metrics of a node like the status of the different
 * components, pending requests in the log unit and tokens generated by the sequencer on the node.
 *
 * <p>Created by zlokhandwala on 4/12/18.
 */
@Data
public class ServerMetrics implements ICorfuPayload<ServerMetrics> {

    /**
     * Current node's Endpoint.
     */
    private final NodeLocator endpoint;

    /**
     * Sequencer metrics of the node.
     */
    private final SequencerMetrics sequencerMetrics;

    public ServerMetrics(NodeLocator endpoint, SequencerMetrics sequencerMetrics) {
        this.endpoint = endpoint;
        this.sequencerMetrics = sequencerMetrics;
    }

    public ServerMetrics(ByteBuf buf) {
        endpoint = NodeLocator.parseString(ICorfuPayload.fromBuffer(buf, String.class));
        sequencerMetrics = ICorfuPayload.fromBuffer(buf, SequencerMetrics.class);
    }

    @Override
    public void doSerialize(ByteBuf buf) {
        ICorfuPayload.serialize(buf, endpoint.toString());
        ICorfuPayload.serialize(buf, sequencerMetrics);
    }
}