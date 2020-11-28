package com.hydra324.announcementBot;

import com.google.cloud.texttospeech.v1beta1.SsmlVoiceGender;
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
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.VoiceChannel;
import discord4j.rest.util.Color;
import discord4j.voice.VoiceConnection;
import org.apache.commons.lang3.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.hydra324.announcementBot.PropertyConstants.LONG_PAUSE;

public class Bot {
    private static final EnvProperty envProperty = (String prop) ->
            Optional.ofNullable(System.getenv(prop))
                    .orElseThrow(() -> new CustomException(String.format("%s is not set in the environment",prop)));
    private static String PREFIX;
    private static final Map<String, Command> commands = new HashMap<>();
    private static String username;

    private static GatewayDiscordClient gateway;
    private static Disposable voiceStateUpdateEventSubscription;
    private static final AudioPlayerManager playerManager;
    private static final TTSAudioResultHandler ttsAudioResultHandler;
    private static final TTSFrameProvider ttsFrameProvider;
    private static boolean subscribedToVoiceStateUpdates;

    static {
        playerManager = new DefaultAudioPlayerManager();
        playerManager.getConfiguration().setFrameBufferFactory(NonAllocatingAudioFrameBuffer::new);
        AudioSourceManagers.registerLocalSource(playerManager);
        AudioPlayer player = playerManager.createPlayer();
        TrackScheduler trackScheduler = new TrackScheduler(player);
        player.addListener(trackScheduler);
        ttsAudioResultHandler = new TTSAudioResultHandler(trackScheduler);
        ttsFrameProvider = new TTSFrameProvider(player);
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
        // get prefix
        PREFIX = getPrefix();
        // not subscribed initially
        subscribedToVoiceStateUpdates = false;

        commands.put("ping", event -> event.getMessage().getChannel().flatMap(messageChannel -> messageChannel.createMessage(String.format("sup? wanna see my commands? use %shelp",PREFIX))).then());
        commands.put("help", event -> event.getMessage().getChannel().flatMap(messageChannel -> messageChannel.createEmbed( spec -> spec
                .setColor(Color.MEDIUM_SEA_GREEN)
                .setTitle("Nice to have you here mate!")
                .addField("To make the bot join voice channel:",String.format("```%sjoin```",PREFIX), false)
                .addField("To make the bot leave voice channel:",String.format("```%sleave```",PREFIX),false)
                .addField("Wanna have some fun with my tts?",String.format("```%stts```",PREFIX), false))).then());
        commands.put("join", event -> Mono.justOrEmpty(event.getMember())
                .flatMap(Member::getVoiceState)
                .flatMap(voiceState -> voiceState.getChannel()
                        .filter(Objects::nonNull)
                        .flatMap(channel -> channel.join(spec -> spec.setProvider(ttsFrameProvider)))
                        .then(Mono.fromCallable(() -> {if(!subscribedToVoiceStateUpdates){voiceStateUpdateEventSubscription = subscribeToVoiceStateUpdates();} return "Joined Voice channel";})))
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
                    subscribedToVoiceStateUpdates = false;
                    return voiceConnection.disconnect().then(Mono.just("Disconnected from Voice connection"));
                })
                .switchIfEmpty(event.getMessage().getChannel()
                        .flatMap(channel ->
                                channel.createEmbed(spec -> spec
                                        .setColor(Color.RED)
                                        .setTitle(String.valueOf(Character.toChars(10060)) + " Bot isn't in voice channel")))
                        .then(Mono.just("No voice connection to disconnect from")))
                .then());
        commands.put("tts",event ->
                Mono.justOrEmpty(event.getMember()).filter(member -> !member.isBot()).flatMap(member -> {
                    String name = member.getDisplayName();
                    // check if voice connection is already established. If yes then use exiting connection. If no then join the channel with most active users
                    return gateway.getVoiceConnectionRegistry().getVoiceConnection(event.getGuildId().get())
                            .switchIfEmpty(getActiveVoiceChannel(event))
                            .flatMap( voiceConnection -> {
                                // We are going to subscribe to voice state update events if not subscribed yet
                                if(!subscribedToVoiceStateUpdates){
                                    voiceStateUpdateEventSubscription = subscribeToVoiceStateUpdates();
                                }
                                return ttsFrameProvider.tts(StringUtils.left( name+ "says"+ LONG_PAUSE + event.getMessage().getContent().substring((PREFIX+"tts").length()+1), 500), SsmlVoiceGender.MALE)
                                        .filter(value -> value)
                                        .map(value -> playerManager.loadItem("output.ogg", ttsAudioResultHandler));
                            });
                }).then());

        DiscordClient client = DiscordClient.create(envProperty.getEnvProperty(PropertyConstants.BOT_TOKEN));
        gateway = client.login().block();

        gateway.getEventDispatcher().on(MessageCreateEvent.class)
                .flatMap(event -> Mono.just(event.getMessage().getContent())
                        .flatMap(content -> Flux.fromIterable(commands.entrySet())
                                .filter(entry -> content.toLowerCase().startsWith(PREFIX + entry.getKey()))
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
        subscribedToVoiceStateUpdates = true;
        return gateway.getEventDispatcher().on(VoiceStateUpdateEvent.class)
                .filterWhen(voiceStateUpdateEvent -> voiceStateUpdateEvent.getCurrent().getUser().map(user -> !user.isBot()))
                .flatMap(voiceStateUpdateEvent -> {
                    username = voiceStateUpdateEvent.getCurrent().getUser().map(User::getUsername).block();
                    if(Bot.hasJoinedChannel(voiceStateUpdateEvent) == UserVoiceState.CONNECTED){
                        return ttsFrameProvider.tts(username + "has joined", SsmlVoiceGender.FEMALE);
                    } else if (Bot.hasJoinedChannel(voiceStateUpdateEvent) == UserVoiceState.DISCONNECTED){
                        return ttsFrameProvider.tts(username + "has left", SsmlVoiceGender.FEMALE);
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

    private static Mono<VoiceConnection> getActiveVoiceChannel(MessageCreateEvent event) {
        return event.getGuild()
                .flatMapMany(Guild::getVoiceStates)
                .flatMap(VoiceState::getChannel)
                .groupBy(VoiceChannel::getId)
                .sort((group1, group2) -> (int) (group1.count().block() - group2.count().block()))
                .takeLast(1)
                .flatMap(groupedFlux -> groupedFlux.takeLast(1))
                .singleOrEmpty()
                .flatMap(voiceChannel -> voiceChannel.join(spec -> spec.setProvider(ttsFrameProvider)));
    }
}
