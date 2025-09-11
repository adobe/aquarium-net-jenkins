#!/bin/sh

if [ ! -f aquarium-fish-java-shaded.jar ]; then
    aquarium_fish_version=$(mvn --batch-mode help:evaluate -Dexpression=aquarium-fish-java.version -q -DforceStdout)

    echo 'Download and install release artifact from github'
    curl -sLo "aquarium-fish-java-shaded-${aquarium_fish_version}.jar" "https://github.com/adobe/aquarium-fish/releases/download/v${aquarium_fish_version}/aquarium-fish-java-${aquarium_fish_version}-shaded.jar"
    mvn --batch-mode install:install-file -Dfile=aquarium-fish-java-shaded-${aquarium_fish_version}.jar -DgroupId=com.adobe.ci.aquarium -DartifactId=aquarium-fish-java -Dversion=${aquarium_fish_version} -Dclassifier=shaded -Dpackaging=jar
fi

echo 'Verify with Maven'
mvn --batch-mode clean package
