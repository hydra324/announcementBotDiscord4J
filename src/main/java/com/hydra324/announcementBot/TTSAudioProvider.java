package com.hydra324.announcementBot;

import discord4j.voice.AudioProvider;

import java.nio.ByteBuffer;

public class TTSAudioProvider extends AudioProvider {
    private byte[] out;
    private int index;
    private ByteBuffer lastFrame;

    public TTSAudioProvider() {
        super(ByteBuffer.allocate(TTSFrameProvider.AUDIO_FRAME));
        this.out = new byte[0];
    }

    @Override
    public boolean provide() {
        boolean canProvide = index < out.length;
        if(canProvide){
            // write tts audio frame to the buffer
            lastFrame = ByteBuffer.wrap(out,index,TTSFrameProvider.AUDIO_FRAME);
            index += TTSFrameProvider.AUDIO_FRAME;
            getBuffer().put(lastFrame);
            getBuffer().flip();

            if(index >= out.length){
                System.out.print("TTS Completed");
            }
        }
        return canProvide;
    }

    public void announce(String text){
        // 20ms polling should not provide anything before calling this method, so setting index as large as possible
        this.index = Integer.MAX_VALUE;

        // get the audio data from googleTTS and process it as stereo byte array audio data.
        try{
            this.out = new TTSFrameProvider().tts(text);
        } catch (Exception e){
            e.printStackTrace();
        }

        // 20ms polling gives back frames now
        this.index = 0;
    }
}
