package com.hydra324.announcementBot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.VoiceStateUpdateEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.VoiceState;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.User;
import discord4j.voice.VoiceConnection;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class Bot {
    private static final Map<String, Command> commands = new HashMap<>();
    private static String username;

    private static GatewayDiscordClient gateway;
    private static Disposable voiceStateUpdateEventSubscription;
    private static AudioPlayerManager playerManager;
    private static TTSAudioResultHandler ttsAudioResultHandler;
    private static TTSFrameProvider ttsFrameProvider;

    static {
        playerManager = new DefaultAudioPlayerManager();
        playerManager.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        AudioSourceManagers.registerLocalSource(playerManager);
        AudioPlayer player = playerManager.createPlayer();
        TrackScheduler trackScheduler = new TrackScheduler(player);
        player.addListener(trackScheduler);
        ttsAudioResultHandler = new TTSAudioResultHandler(trackScheduler);
        ttsFrameProvider = new TTSFrameProvider(player);

        commands.put("ping", event -> event.getMessage().getChannel().flatMap(messageChannel -> messageChannel.createMessage("Pong!")).then());
        commands.put("join", event ->
            Mono.justOrEmpty(event.getMember())
                    .flatMap(Member::getVoiceState)
                    .flatMap(VoiceState::getChannel)
                    .flatMap(channel -> channel.join(spec -> spec.setProvider(ttsFrameProvider)))
                    .map(voiceConnection -> voiceStateUpdateEventSubscription = subscribeToVoiceStateUpdates())
                    .then());
        commands.put("leave", event -> {
            voiceStateUpdateEventSubscription.dispose();
            return gateway.getVoiceConnectionRegistry().getVoiceConnection(event.getGuildId().get()).flatMap(VoiceConnection::disconnect);
        });
    }

    enum UserVoiceState {
        CONNECTED,
        DISCONNECTED,
        UNCHANGED,
        NEWCHANNEL,
    }

    public static void main(String[] args) throws CustomException {
        final String TOKEN = Optional.ofNullable(System.getenv("ANNOUNCEMENT_BOT_TOKEN")).orElseThrow(
                () -> new CustomException("ANNOUNCEMENT_BOT_TOKEN is not set in the environment"));

        DiscordClient client = DiscordClient.create(TOKEN);
        gateway = client.login().block();

        gateway.getEventDispatcher().on(MessageCreateEvent.class)
                // 3.1 Message.getContent() is a String
                .flatMap(event -> Mono.just(event.getMessage().getContent())
                        .flatMap(content -> Flux.fromIterable(commands.entrySet())
                                // We will be using $ as our "prefix" to any command in the system.
                                .filter(entry -> content.startsWith('$' + entry.getKey()))
                                .flatMap(entry -> entry.getValue().execute(event))
                                .next()))
                .subscribe();

        gateway.onDisconnect().block();
    }

     private static UserVoiceState hasJoinedChannel(VoiceStateUpdateEvent event){
        VoiceState oldVoiceState = event.getOld().orElse(null);
        VoiceState newVoiceState = event.getCurrent();
        // looks clumsy - can be simplified
        if(oldVoiceState == null){
            return UserVoiceState.CONNECTED;
        }
        if(!oldVoiceState.getChannelId().isPresent() && newVoiceState.getChannelId().isPresent()){
            return UserVoiceState.CONNECTED;
        }
        if(oldVoiceState.getChannelId().isPresent() && newVoiceState.getChannelId().isPresent()){
            if(oldVoiceState.getChannelId().get().asLong() == newVoiceState.getChannelId().get().asLong()){
                return UserVoiceState.UNCHANGED;
            }
            else return UserVoiceState.NEWCHANNEL;
        }
        return UserVoiceState.DISCONNECTED;
    }

    private static Disposable subscribeToVoiceStateUpdates(){
        return gateway.getEventDispatcher().on(VoiceStateUpdateEvent.class)
                .filterWhen(voiceStateUpdateEvent -> voiceStateUpdateEvent.getCurrent().getUser().map(user -> !user.isBot()))
                .flatMap(voiceStateUpdateEvent -> {
                    username = voiceStateUpdateEvent.getCurrent().getUser().map(User::getUsername).block();
                    if(Bot.hasJoinedChannel(voiceStateUpdateEvent) == UserVoiceState.CONNECTED){
                        return ttsFrameProvider.tts(username + "has joined");
                    } else if (Bot.hasJoinedChannel(voiceStateUpdateEvent) == UserVoiceState.DISCONNECTED){
                        return ttsFrameProvider.tts(username + "has left");
                    } else {
                        return Mono.just(false);
                    }
                })
                .filter(value -> value)
                .map(value -> playerManager.loadItem("output.ogg", ttsAudioResultHandler))
                .then().subscribe();
    }
}
