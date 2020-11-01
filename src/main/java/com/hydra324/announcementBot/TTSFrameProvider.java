package com.hydra324.announcementBot;

import com.google.cloud.texttospeech.v1beta1.*;
import com.google.protobuf.ByteString;

public class TTSFrameProvider {
    public static final int AUDIO_FRAME = 3840; // 48000 / 50 (number of 20 ms in a second) * 2 (16-bit samples) * 2 (channels)



    TTSFrameProvider() {}

     byte[] tts(String text) throws Exception{
        try(TextToSpeechClient client = TextToSpeechClient.create()){
            SynthesisInput input = SynthesisInput.newBuilder().setText(text).build();

            VoiceSelectionParams voiceSelectionParams = VoiceSelectionParams.newBuilder().setLanguageCode("en-US").setSsmlGender(SsmlVoiceGender.FEMALE).build();

            AudioConfig audioConfig = AudioConfig.newBuilder().setAudioEncoding(AudioEncoding.OGG_OPUS).setSampleRateHertz(48_000).build();

            SynthesizeSpeechResponse response = client.synthesizeSpeech(input, voiceSelectionParams, audioConfig);

            ByteString audioContents = response.getAudioContent();

            return audioContents.toByteArray();
        }
    }

    /* Deprecated for this branch - no need to covert to stereo since we are using lava player which internally does convert based on audio encoding */
    private byte[] convertToStereoAndPadToFitFrameSize(byte[] pcm) {
        // Three things need to happen - big endian, stereo, pad to a multiple of 3840
        // Add a frame of silence at the beginning so that the sound doesn't clip weirdly
        byte[] converted = new byte[AUDIO_FRAME + pcm.length * 2 + (AUDIO_FRAME - pcm.length * 2 % AUDIO_FRAME)];
        // ensures converted is a multiple of AUDIO_FRAME
        for(int i = AUDIO_FRAME; i < pcm.length; i += 2) {
            short reversed = Short.reverseBytes((short) ((pcm[i] << 8) | (pcm[i + 1] & 0xFF)));
            byte low = (byte) (reversed >> 8);
            byte high = (byte) (reversed & 0x00FF);

            // reverse bytes and double to convert to stereo
            converted[i * 2] = low;
            converted[i * 2 + 1] = high;
            converted[i * 2 + 2] = low;
            converted[i * 2 + 3] = high;
        }

        return converted;

    }
}
