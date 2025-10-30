#!/bin/sh

echo 'Find required version of the aquarium-fish client artifact'
aquarium_fish_version=$(mvn --batch-mode help:evaluate -Dexpression=aquarium-fish-java.version -q -DforceStdout)

if [ ! -f "aquarium-fish-java-shaded-${aquarium_fish_version}.jar" ]; then
    echo 'Download and install release artifact from github'
    curl -sLo "aquarium-fish-java-shaded-${aquarium_fish_version}.jar" "https://github.com/adobe/aquarium-fish/releases/download/v${aquarium_fish_version}/aquarium-fish-java-${aquarium_fish_version}-shaded.jar"
    mvn --batch-mode install:install-file -Dfile=aquarium-fish-java-shaded-${aquarium_fish_version}.jar -DgroupId=com.adobe.ci.aquarium -DartifactId=aquarium-fish-java -Dversion=${aquarium_fish_version} -Dclassifier=shaded -Dpackaging=jar
fi

os_name=$(uname -s)

case "$os_name" in
Linux)
    os_name=linux
    ;;
Darwin)
    os_name=darwin
    ;;
esac

if [ -d ../aquarium-fish ]; then
    echo 'Using latest local aquarium-fish binary for integration tests'
    rm -f aquarium-fish
    ln -s $(ls -t ../aquarium-fish/aquarium-fish-*.${os_name}_$(uname -m) | head -1) aquarium-fish
else
    echo "Download aquarium-fish v${aquarium_fish_version} from release for integration tests"
    curl -sLo aquarium-fish.tar.xz https://github.com/adobe/aquarium-fish/releases/download/v${aquarium_fish_version}/aquarium-fish-v${aquarium_fish_version}.${os_name}_$(uname -m).tar.xz
    tar xf aquarium-fish.tar.xz
fi

if [ "x$(docker images -q jenkins-agent-docker:java11)" = x ]; then
    echo 'Build docker image with jenkins agent'
    docker build -t jenkins-agent-docker:java11 -f src/test/resources/jenkins-agent-docker/Dockerfile.java11 src/test/resources/jenkins-agent-docker
fi

echo 'Verify with Maven'
mvn --batch-mode --update-snapshots clean verify -P integration-tests
