FROM openjdk:11.0.9.1-jdk AS TEMP_BUILD_IMAGE
ENV APP_HOME=/usr/app/
WORKDIR $APP_HOME
COPY build.gradle settings.gradle gradlew $APP_HOME
COPY gradle $APP_HOME/gradle
RUN ./gradlew build shadowJar || return 0
COPY . .
RUN ./gradlew build shadowJar

FROM openjdk:11.0.9.1-jdk
ENV ARTIFACT_NAME=Announcement-Bot-1.0-SNAPSHOT-fat.jar
ENV APP_HOME=/usr/app/
WORKDIR $APP_HOME
COPY --from=TEMP_BUILD_IMAGE $APP_HOME/build/libs/$ARTIFACT_NAME .
CMD ["java","-jar","${ARTIFACT_NAME}"]