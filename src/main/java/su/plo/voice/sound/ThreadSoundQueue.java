package su.plo.voice.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.apache.commons.lang3.tuple.Pair;
import su.plo.voice.Voice;
import su.plo.voice.common.packets.udp.VoiceServerPacket;
import su.plo.voice.gui.settings.MicTestButton;
import su.plo.voice.socket.SocketClientUDPQueue;
import su.plo.voice.utils.Utils;

import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ThreadSoundQueue extends Thread {
    public ConcurrentLinkedQueue<VoiceServerPacket> queue = new ConcurrentLinkedQueue<>();
    private SourceDataLine speaker;
    private FloatControl gainControl;
    private boolean stopped;
    private long lastPacketTime;
    public final UUID from;
    public long lastSequenceNumber;

    private final OpusDecoder opusDecoder;
    private double lastOcclusion = -1;

    public ThreadSoundQueue(UUID from) {
        this.from = from;
        this.lastPacketTime = System.currentTimeMillis() - 300L;
        this.lastSequenceNumber = -1L;
        this.opusDecoder = new OpusDecoder(Recorder.sampleRate, Recorder.frameSize, Recorder.mtuSize);
    }

    public boolean canKill() {
        return System.currentTimeMillis() - lastPacketTime > 30_000L;
    }

    public boolean canClose() {
        return System.currentTimeMillis() - lastPacketTime > 1000L;
    }

    public void closeAndKill() {
        if (speaker != null) {
            speaker.close();
        }
        stopped = true;

        this.opusDecoder.close();
    }

    public boolean isClosed() {
        return stopped;
    }

    public void addQueue(VoiceServerPacket packet) {
        if(packet == null) {
            return;
        }

        this.queue.offer(packet);

        synchronized (this) {
            this.notify();
        }
    }

    @Override
    public void run() {
        try {
            speaker = DataLines.getSpeaker();
            if(speaker == null) {
                Voice.LOGGER.error("Failed to open speaker");
                return;
            }
            speaker.open(Recorder.stereoFormat);
            gainControl = (FloatControl) speaker.getControl(FloatControl.Type.MASTER_GAIN);
        } catch (Throwable e) {
            e.printStackTrace();
            if (speaker != null) {
                speaker.stop();
                speaker.flush();
                speaker.close();
            }
        }

        Minecraft minecraft = Minecraft.getInstance();

        while(!stopped) {
            if(!this.queue.isEmpty()) {
                VoiceServerPacket packet = this.queue.poll();

                if (packet.getData().length == 0) {
                    speaker.stop();
                    speaker.flush();
                    lastSequenceNumber = -1L;
                    lastOcclusion = -1;
                    continue;
                }

                if (this.lastSequenceNumber >= 0 && packet.getSequenceNumber() <= this.lastSequenceNumber) {
                    continue;
                }
                lastPacketTime = System.currentTimeMillis();

                if(MicTestButton.micActive) {
                    continue;
                }

                // Filling the speaker with silence for one packet size
                // to build a small buffer to compensate for network latency
                if (speaker.getBufferSize() - speaker.available() <= 0) {
                    byte[] data = new byte[Math.min(Recorder.frameSize * 6, speaker.getBufferSize() - Recorder.frameSize)];
                    speaker.write(data, 0, data.length);
                }
                PlayerEntity player = minecraft.level.getPlayerByUUID(this.from);
                if(player == null) {
                    lastSequenceNumber = -1L;
                    lastOcclusion = -1;
                    continue;
                }

                ClientPlayerEntity clientPlayer = minecraft.player;

                float distance = (float) player.position().distanceTo(clientPlayer.position());
                float percentage = 1F;

                int maxDistance = packet.getDistance();
                if(distance > maxDistance) {
                    lastSequenceNumber = -1L;
                    lastOcclusion = -1;
                    continue;
                }
                int fadeDistance = packet.getDistance() > Voice.serverConfig.maxDistance
                        ? maxDistance / Voice.serverConfig.priorityFadeDivisor
                        : maxDistance / Voice.serverConfig.fadeDivisor;

                if (distance > fadeDistance) {
                    percentage = 1F - (Math.min((distance - fadeDistance) / (maxDistance - fadeDistance), 1F));
                }

                if(Voice.config.occlusion) {
                    double occlusion = Occlusion.getOccludedPercent(player.level, clientPlayer, player.position());
                    if(lastOcclusion >= 0) {
                        if(occlusion > lastOcclusion) {
                            lastOcclusion = Math.max(lastOcclusion + 0.05, 0.0D);
                        } else {
                            lastOcclusion = Math.max(lastOcclusion - 0.05, occlusion);
                        }

                        occlusion = lastOcclusion;
                    }
                    percentage *= (float) (1D - occlusion);
                    if(lastOcclusion == -1) {
                        lastOcclusion = occlusion;
                    }
                }

                gainControl.setValue(Math.min(Math.max(Utils.percentageToDB(percentage * (float) Voice.config.voiceVolume), gainControl.getMinimum()), gainControl.getMaximum()));

                if (lastSequenceNumber >= 0) {
                    int packetsToCompensate = (int) (packet.getSequenceNumber() - (lastSequenceNumber + 1));
                    if(packetsToCompensate < 10) {
                        for (int i = 0; i < packetsToCompensate; i++) {
                            if (speaker.available() < Recorder.frameSize) {
                                break;
                            }
                            writeToSpeaker(opusDecoder.decode(null), player, maxDistance);
                        }
                    }
                }

                lastSequenceNumber = packet.getSequenceNumber();
                writeToSpeaker(opusDecoder.decode(packet.getData()), player, maxDistance);
            } else {
                if (speaker.getBufferSize() - speaker.available() <= 0 && speaker.isActive() && canClose()) {
                    speaker.stop();
                    speaker.flush();
                    lastSequenceNumber = -1L;
                    lastOcclusion = -1;
                    SocketClientUDPQueue.talking.remove(this.from);
                }

                try {
                    synchronized (this) {
                        this.wait(10L);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void writeToSpeaker(byte[] monoData, PlayerEntity player, int maxDistance) {
        if(monoData.length == 0) {
            return;
        }

        Pair<Float, Float> stereoVolume = Utils.getStereoVolume(Minecraft.getInstance(), player.position(), maxDistance);
        byte[] stereo = Utils.convertToStereo(monoData, stereoVolume.getLeft(), stereoVolume.getRight());

        speaker.write(stereo, 0, stereo.length);
        speaker.start();
    }
}
