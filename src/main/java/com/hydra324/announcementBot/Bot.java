package com.hydra324.announcementBot;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.track.playback.NonAllocatingAudioFrameBuffer;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.VoiceStateUpdateEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.VoiceState;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.voice.AudioProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class Bot {
    private static final Map<String, Command> commands = new HashMap<>();
    private static String username;

    static {
        commands.put("ping", event -> event.getMessage().getChannel().flatMap(messageChannel -> messageChannel.createMessage("Pong!")).then());
    }

    enum UserVoiceState {
        CONNECTED,
        DISCONNECTED,
        UNCHANGED,
        NEWCHANNEL,
    }

    public static void main(String[] args) throws CustomException {

        // Creates AudioPlayer instances and translates URLs to AudioTrack instances
        final AudioPlayerManager playerManager = new DefaultAudioPlayerManager();
// This is an optimization strategy that Discord4J can utilize. It is not important to understand
        playerManager.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
// Allow playerManager to parse remote sources like YouTube links
        AudioSourceManagers.registerRemoteSources(playerManager);
// Create an AudioPlayer so Discord4J can receive audio data
        final AudioPlayer player = playerManager.createPlayer();
// We will be creating LavaPlayerAudioProvider in the next step
        AudioProvider provider = new LavaPlayerAudioProvider(player);

        final String TOKEN = Optional.ofNullable(System.getenv("ANNOUNCEMENT_BOT_TOKEN")).orElseThrow(
                () -> new CustomException("ANNOUNCEMENT_BOT_TOKEN is not set in the environment"));

//        // Build the audio provider
//        TTSAudioProvider provider;
//        provider = new TTSAudioProvider();

        final DiscordClient client = DiscordClient.create(TOKEN);
        final GatewayDiscordClient gateway = client.login().block();

        gateway.getEventDispatcher().on(MessageCreateEvent.class)
                // 3.1 Message.getContent() is a String
                .flatMap(event -> Mono.just(event.getMessage().getContent())
                        .flatMap(content -> Flux.fromIterable(commands.entrySet())
                                // We will be using ! as our "prefix" to any command in the system.
                                .filter(entry -> content.startsWith('!' + entry.getKey()))
                                .flatMap(entry -> entry.getValue().execute(event))
                                .next()))
                .subscribe();


        gateway.getEventDispatcher().on(VoiceStateUpdateEvent.class)
                .filterWhen(voiceStateUpdateEvent -> voiceStateUpdateEvent.getCurrent().getUser().map(user -> !user.isBot()))
                .flatMap(voiceStateUpdateEvent -> {
                    username = voiceStateUpdateEvent.getCurrent().getUser().map(User::getUsername).block();
                    Snowflake announcementsChannelId = voiceStateUpdateEvent.getCurrent().getGuild().map(guild -> guild.getChannels().filter(channel -> channel.getName().equalsIgnoreCase("announcements")).map(Channel::getId).single().block()).block();
                    TextChannel announcementsChannel = gateway.getChannelById(announcementsChannelId).ofType(TextChannel.class).block();
                    if(Bot.hasJoinedChannel(voiceStateUpdateEvent) == UserVoiceState.CONNECTED){
                        announcementsChannel.createMessage(username + "has joined").block();
                        return voiceStateUpdateEvent.getCurrent().getChannel().flatMap(voiceChannel -> voiceChannel.join(voiceChannelJoinSpec -> voiceChannelJoinSpec.setProvider(provider)));
                    } else if (Bot.hasJoinedChannel(voiceStateUpdateEvent) == UserVoiceState.DISCONNECTED){
                        announcementsChannel.createMessage(username + "has left").block();
                        return voiceStateUpdateEvent.getOld().get().getChannel().flatMap(voiceChannel -> voiceChannel.join(voiceChannelJoinSpec -> voiceChannelJoinSpec.setProvider(provider)));
                    } else {
                        return Mono.empty();
                    }
                })
                .map(voiceConnection -> {
//                    provider.announce(username);
                    return voiceConnection.disconnect();
                }).subscribe();

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
}
