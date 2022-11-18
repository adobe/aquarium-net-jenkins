# [Aquarium Net Jenkins](https://github.com/adobe/aquarium-net-jenkins)

[![CI](https://github.com/adobe/aquarium-net-jenkins/actions/workflows/main.yml/badge.svg?branch=main)](https://github.com/adobe/aquarium-net-jenkins/actions/workflows/main.yml)

[Aquarium](https://github.com/adobe/aquarium-fish/wiki/Aquarium) resources manager jenkins plugin
to allocate the required resources on demand. 

## Requirements

* Jenkins >= 2.164.3

## Build

To build just download the latest maven from apache and run `mvn clean package` - it will create
the `./target/aquarium-net-jenkins.hpi` file which you can use to install in Jenkins.

If you want to use custom Fish OpenAPI specification during the build - just set the profile to
`Local`: `mvn clean package -P Local`

## Usage

Just install it to your jenkins, specify some Aquarium Fish node API address and choose credentials.
The plugin will automatically connect to the cluster, receive the other nodes addresses to maintain
reliable connection, get the cluster labels configuration and will allow to edit them as needed (if
the user you use have permissions to do that).

Now when the build will be added to queue - if the cluster have the label in stock and the limits
are met the plugin will create the node to allow connect the jenkins and place the resource request
in the cluster.

Aquarium Fish cluster will decide which node can handle the resource request, run the resource and
connect to the created agent node to serve the build needs.

### Pipeline steps

You can use the next steps in the pipeline:

* `aquariumSnapshot()` (`full: false`)
   Trigger the current Aquarium Application to snapshot the current state. What actually will be
   captured really depends on the Label driver, but in general the rules are:
     * `full: false` - partial snapshot, just the attached disks except for the root disk.
     * `full: true` - full snapshot including root disk and, if possible, memory of the running env.

## Implementation

The implementation is still PoC and not perfect in any way. For now it's mostly working.

Originally was based on [Scripted Cloud Plugin](https://plugins.jenkins.io/scripted-cloud-plugin/),
but later absorbed alot from [Kubernetes Plugin](https://plugins.jenkins.io/kubernetes/).
