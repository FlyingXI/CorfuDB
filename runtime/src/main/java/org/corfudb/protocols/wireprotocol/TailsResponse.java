package org.corfudb.protocols.wireprotocol;

import io.netty.buffer.ByteBuf;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
@RequiredArgsConstructor
public class TailsResponse implements ICorfuPayload<TailsResponse> {

    final long logTail;

    final Map<UUID, Long> streamTails;

    public TailsResponse(ByteBuf buf) {
        streamTails = new HashMap<>();
        logTail = buf.readLong();
        int numStreams = buf.readInt();
        for (int x = 0; x < numStreams; x++) {
            long lsb = buf.readLong();
            long msb = buf.readLong();
            long tail = buf.readLong();
            streamTails.put(new UUID(msb, lsb), tail);
        }
    }

    @Override
    public void doSerialize(ByteBuf buf) {
        buf.writeLong(logTail);
        buf.writeInt(streamTails.size());
        for (Map.Entry<UUID, Long> stream : streamTails.entrySet()) {
            buf.writeLong(stream.getKey().getLeastSignificantBits());
            buf.writeLong(stream.getKey().getMostSignificantBits());
        }
    }
}
