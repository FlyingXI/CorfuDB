package org.corfudb.util.serializer;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.runtime.CorfuRuntime;

import java.io.*;
import java.lang.reflect.Field;
import java.util.UUID;

/**
 * Created by mwei on 2/10/16.
 */
@Slf4j
public class JSONSerializer implements ISerializer {

    private static final Gson gson = new Gson();

    /**
     * Deserialize an object from a given byte buffer.
     *
     * @param b The bytebuf to deserialize.
     * @return The deserialized object.
     */
    @Override
    public Object deserialize(ByteBuf b, CorfuRuntime rt) {
        int classNameLength = b.readShort();
        byte[] classNameBytes = new byte[classNameLength];
        b.readBytes(classNameBytes, 0, classNameLength);
        String className = new String(classNameBytes);
        if (className.equals("null"))
        {
            return null;
        }
        else if (className.equals("CorfuSMRObject"))
        {
            int SMRClassNameLength = b.readShort();
            byte[] SMRClassNameBytes = new byte[SMRClassNameLength];
            b.readBytes(SMRClassNameBytes, 0, SMRClassNameLength);
            String SMRClassName = new String(SMRClassNameBytes);
            try {
                return rt.getObjectsView().open(new UUID(b.readLong(), b.readLong()), Class.forName(SMRClassName));
            } catch (ClassNotFoundException cnfe) {
                log.error("Exception during deserialization!", cnfe);
                throw new RuntimeException(cnfe);
            }
        }
        else {
            try (ByteBufInputStream bbis = new ByteBufInputStream(b)) {
                try (InputStreamReader r = new InputStreamReader(bbis)) {
                    return gson.fromJson(r, Class.forName(className));
                }
            } catch (IOException | ClassNotFoundException ie) {
                log.error("Exception during deserialization!", ie);
                throw new RuntimeException(ie);
            }
        }
    }

    /**
     * Serialize an object into a given byte buffer.
     *
     * @param o The object to serialize.
     * @param b The bytebuf to serialize it into.
     */
    @Override
    public void serialize(Object o, ByteBuf b) {
        String className = o == null ? "null" :  o.getClass().getName();
        if (className.contains("$ByteBuddy$"))
        {
            String SMRClass = className.split("\\$")[0];
            className = "CorfuSMRObject";
            byte[] classNameBytes = className.getBytes();
            b.writeShort(classNameBytes.length);
            b.writeBytes(classNameBytes);
            byte[] SMRClassNameBytes = SMRClass.getBytes();
            b.writeShort(SMRClassNameBytes.length);
            b.writeBytes(SMRClassNameBytes);
            try {
                Field f = o.getClass().getDeclaredField("_corfuStreamID");
                f.setAccessible(true);
                UUID id = (UUID) f.get(o);
                b.writeLong(id.getMostSignificantBits());
                b.writeLong(id.getLeastSignificantBits());
            } catch (NoSuchFieldException | IllegalAccessException nsfe)
            {
                log.error("Error serializing fields");
                throw new RuntimeException(nsfe);
            }
        }
        else {
            byte[] classNameBytes = className.getBytes();
            b.writeShort(classNameBytes.length);
            b.writeBytes(classNameBytes);
            if (o == null) { return; }
            try (ByteBufOutputStream bbos = new ByteBufOutputStream(b))
            {
                try (OutputStreamWriter osw = new OutputStreamWriter(bbos))
                {
                    gson.toJson(o, osw);
                }
            }
            catch (IOException ie)
            {
                log.error("Exception during serialization!", ie);
            }
        }
    }
}
