package su.plo.voice.common.packets.udp;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import lombok.AllArgsConstructor;
import lombok.Getter;
import su.plo.voice.common.packets.Packet;

import java.io.IOException;

@AllArgsConstructor
public class VoiceEndClientPacket implements Packet {
    @Getter
    private short distance;

    public VoiceEndClientPacket() {}

    @Override
    public void read(ByteArrayDataInput buf) throws IOException {
        this.distance = buf.readShort();
    }

    @Override
    public void write(ByteArrayDataOutput buf) throws IOException {
        buf.writeShort(distance);
    }
}