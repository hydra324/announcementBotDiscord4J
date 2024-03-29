package com.hydra324.announcementBot;

import discord4j.core.event.domain.message.MessageCreateEvent;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface Command {
    Mono<Void> execute(MessageCreateEvent event);
}
