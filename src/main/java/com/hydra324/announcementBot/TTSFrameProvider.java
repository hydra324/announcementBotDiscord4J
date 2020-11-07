package com.hydra324.announcementBot;

import com.google.cloud.texttospeech.v1beta1.AudioConfig;
import com.google.cloud.texttospeech.v1beta1.AudioEncoding;
import com.google.cloud.texttospeech.v1beta1.SsmlVoiceGender;
import com.google.cloud.texttospeech.v1beta1.SynthesisInput;
import com.google.cloud.texttospeech.v1beta1.SynthesizeSpeechResponse;
import com.google.cloud.texttospeech.v1beta1.TextToSpeechClient;
import com.google.cloud.texttospeech.v1beta1.VoiceSelectionParams;
import com.google.protobuf.ByteString;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import discord4j.voice.AudioProvider;
import reactor.core.publisher.Mono;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class TTSFrameProvider extends AudioProvider {
    private final MutableAudioFrame frame = new MutableAudioFrame();
    private final AudioPlayer player;

    public TTSFrameProvider(AudioPlayer player) {
        super(ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize()));
        this.player = player;
        this.frame.setBuffer(getBuffer());
    }

    @Override
    public boolean provide() {
        // AudioPlayer writes audio data to its AudioFrame
        final boolean didProvide = player.provide(frame);
        // If audio was provided, flip from write-mode to read-mode
        if (didProvide) {
            getBuffer().flip();
        }
        return didProvide;
    }

    Mono<Boolean> tts(String text){
        try(TextToSpeechClient client = TextToSpeechClient.create()){
            SynthesisInput input = SynthesisInput.newBuilder().setText(text).build();

            VoiceSelectionParams voiceSelectionParams = VoiceSelectionParams.newBuilder().setLanguageCode("en-US").setSsmlGender(SsmlVoiceGender.FEMALE).build();

            AudioConfig audioConfig = AudioConfig.newBuilder().setAudioEncoding(AudioEncoding.OGG_OPUS).setSampleRateHertz(48_000).build();

            SynthesizeSpeechResponse response = client.synthesizeSpeech(input, voiceSelectionParams, audioConfig);

            ByteString audioContents = response.getAudioContent();

            try (OutputStream outputStream = new FileOutputStream("output.ogg")){
                outputStream.write(audioContents.toByteArray());
                System.out.println("Audio content written to file \"output.ogg\"");
                return Mono.just(true);
            } catch (Exception e){
                e.printStackTrace();
                return Mono.just(false);
            }
        } catch (Exception e){
            e.printStackTrace();
            return Mono.just(false);
        }
    }
}
