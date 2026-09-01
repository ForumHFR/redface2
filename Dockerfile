FROM ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31

WORKDIR /workspace

RUN /opt/android-sdk-linux/cmdline-tools/latest/bin/sdkmanager --install "platforms;android-37.0"

ENV HOME=/home/ubuntu \
    GRADLE_USER_HOME=/home/ubuntu/.gradle \
    ANDROID_USER_HOME=/home/ubuntu/.android

USER ubuntu
