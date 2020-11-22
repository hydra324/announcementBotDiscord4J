package com.hydra324.announcementBot;

@FunctionalInterface
public interface EnvProperty {
    String getEnvProperty(String s) throws CustomException;
}
