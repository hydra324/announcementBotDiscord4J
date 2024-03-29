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
import discord4j.rest.util.Color;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class Bot {
    private static final EnvProperty envProperty = (String prop) ->
            Optional.ofNullable(System.getenv(prop))
                    .orElseThrow(() -> new CustomException(String.format("%s is not set in the environment",prop)));
    private static final String PREFIX;
    private static final Map<String, Command> commands = new HashMap<>();
    private static String username;

    private static GatewayDiscordClient gateway;
    private static Disposable voiceStateUpdateEventSubscription;
    private static final AudioPlayerManager playerManager;
    private static final TTSAudioResultHandler ttsAudioResultHandler;
    private static final TTSFrameProvider ttsFrameProvider;

    static {
        playerManager = new DefaultAudioPlayerManager();
        playerManager.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        AudioSourceManagers.registerLocalSource(playerManager);
        AudioPlayer player = playerManager.createPlayer();
        TrackScheduler trackScheduler = new TrackScheduler(player);
        player.addListener(trackScheduler);
        ttsAudioResultHandler = new TTSAudioResultHandler(trackScheduler);
        ttsFrameProvider = new TTSFrameProvider(player);

        // get prefix
        PREFIX = getPrefix();

        commands.put("ping", event -> event.getMessage().getChannel().flatMap(messageChannel -> messageChannel.createMessage(String.format("sup? wanna see my commands? use %shelp",PREFIX))).then());
        commands.put("help", event -> event.getMessage().getChannel().flatMap(messageChannel -> messageChannel.createEmbed( spec -> spec
                .setColor(Color.MEDIUM_SEA_GREEN)
                .setTitle("Nice to have you here mate!")
                .addField("To make the bot join voice channel:",String.format("```%sjoin```",PREFIX), false)
                .addField("To make the bot leave voice channel:",String.format("```%sleave```",PREFIX),false))).then());
        commands.put("join", event -> Mono.justOrEmpty(event.getMember())
                .flatMap(Member::getVoiceState)
                .flatMap(voiceState -> voiceState.getChannel()
                .filter(Objects::nonNull)
                .flatMap(channel -> channel.join(spec -> spec.setProvider(ttsFrameProvider)))
                .then(Mono.fromCallable(() -> {voiceStateUpdateEventSubscription = subscribeToVoiceStateUpdates(); return "Joined Voice channel";})))
                .switchIfEmpty(event.getMessage().getChannel()
                        .flatMap(messageChannel ->
                                messageChannel.createEmbed(spec -> spec
                                        .setColor(Color.RED)
                                        .setTitle(String.valueOf(Character.toChars(10060)) +
                                                " You must be in a voice channel to use this command")))
                        .then(Mono.just("Sent error message")))
                .then());
        commands.put("leave", event -> gateway.getVoiceConnectionRegistry().getVoiceConnection(event.getGuildId().get())
                .flatMap(voiceConnection -> {
                    voiceStateUpdateEventSubscription.dispose();
                    return voiceConnection.disconnect().then(Mono.just("Disconnected from Voice connection"));
                })
                .switchIfEmpty(event.getMessage().getChannel()
                        .flatMap(channel ->
                                channel.createEmbed(spec -> spec
                                        .setColor(Color.RED)
                                        .setTitle(String.valueOf(Character.toChars(10060)) + " Bot isn't in voice channel")))
                        .then(Mono.just("No voice connection to disconnect from")))
                .then());
    }

    enum UserVoiceState {
        CONNECTED,
        DISCONNECTED,
        UNCHANGED,
        NEWCHANNEL,
    }

    public static void main(String[] args) throws CustomException {
        // Check for GOOGLE CREDENTIALS
        envProperty.getEnvProperty(PropertyConstants.GOOGLE_CREDENTIALS);

        DiscordClient client = DiscordClient.create(envProperty.getEnvProperty(PropertyConstants.BOT_TOKEN));
        gateway = client.login().block();

        gateway.getEventDispatcher().on(MessageCreateEvent.class)
                .flatMap(event -> Mono.just(event.getMessage().getContent())
                        .flatMap(content -> Flux.fromIterable(commands.entrySet())
                                .filter(entry -> content.startsWith(PREFIX + entry.getKey()))
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

    private static String getPrefix(){
        try{
            return envProperty.getEnvProperty(PropertyConstants.COMMANDS_PREFIX);
        } catch (CustomException e) {
            // swallow exception and set default prefix, but log trace
            e.printStackTrace();
            return PropertyConstants.DEFAULT_COMMANDS_PREFIX;
        }
    }
}
