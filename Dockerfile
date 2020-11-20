FROM gradle:6.6.0-jdk11 AS TEMP_BUILD_IMAGE
ENV APP_HOME=/home/gradle/src
COPY --chown=gradle:gradle . $APP_HOME
WORKDIR $APP_HOME
COPY . .
RUN gradle build shadowJar --no-daemon

FROM openjdk:11.0.9.1-jre
ENV ARTIFACT_NAME=Announcement-Bot-1.0-SNAPSHOT-fat.jar
ENV APP_HOME=/home/gradle/src
RUN mkdir /app
COPY --from=TEMP_BUILD_IMAGE $APP_HOME/build/libs/$ARTIFACT_NAME /app/$ARTIFACT_NAME
CMD java -jar /app/$ARTIFACT_NAME