# announcementBotDiscord4J

Announcement Bot announce who joins/leaves a discord voice channel

# Tech Stack
- Discord4j
- Google cloud api
- Lavaplayer

# To run the container on your local host machine
### Environment variables to be set
- ANNOUNCEMENT_BOT_TOKEN
- GOOGLE_APPLICATION_CREDENTIALS

Since GOOGLE_APPLICATION_CREDENTIALS is a json file, we have to mount it on the container volume and then set the environment variable to mount point

``docker run -v $GOOGLE_APPLICATION_CREDENTIALS:/tmp/keys/[FILE_NAME].json:ro -e ANNOUNCEMENT_BOT_TOKEN -e GOOGLE_APPLICATION_CREDENTIALS=/tmp/keys/[FILE_NAME].json [IMAGE ID]``
