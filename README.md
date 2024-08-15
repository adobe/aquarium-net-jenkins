# [Aquarium Net Jenkins](https://github.com/adobe/aquarium-net-jenkins)

[![CI](https://github.com/adobe/aquarium-net-jenkins/actions/workflows/main.yml/badge.svg?branch=main)](https://github.com/adobe/aquarium-net-jenkins/actions/workflows/main.yml)

[Aquarium](https://github.com/adobe/aquarium-fish/wiki/Aquarium) resources manager jenkins plugin
to allocate the required resources on demand. 

## Requirements

* Jenkins >= 2.222

## Build

To build just download the latest maven from apache and run `mvn clean package` - it will create
the `./target/aquarium-net-jenkins.hpi` file which you can use to install in Jenkins.

If you want to use custom Fish OpenAPI specification during the build - just set the profile to
`Local`: `mvn clean package -P Local`

## Usage

Just install it to your jenkins, specify some Aquarium Fish node API address and choose credentials.
The plugin will automatically connect to the cluster, receive the other nodes addresses to maintain
reliable connection, get the cluster labels configuration and will allow to use them as needed.

To utilize the Label you must specify it as name for your node or label expression. Also you can
use specific version of the label by adding suffix with colon and number to the label name like
`your-label-name:55` - so your release build will be pinned to the version of the build environment
forever.

Now when the build will be added to queue - if the cluster have the label in stock and the limits
are met the plugin will create the node to allow connect the jenkins and place the resource request
(Application) in the cluster.

Aquarium Fish cluster will decide which node can handle the resource request, run the resource and
connect to the created agent node to serve the build needs.

### Pipeline steps

You can use the next steps in the pipeline:

* `aquariumCreateSnapshot()` (`full: false`)
   Trigger the current Aquarium Application to snapshot the current state. What actually will be
   captured really depends on the used driver, but in general the rules are:
     * `full: false` - partial snapshot, just the attached disks except for the root disk.
     * `full: true` - full snapshot including root disk and, if possible, memory of the running env.

* `aquariumCreateImage()` (`full: false`)
   Trigger the current Aquarium Application to create an image of the current worker. What actually
   will be captured really depends on the used driver, but in general the rules are:
     * `full: false` - root image, just the root disk without the attached disks.
     * `full: true` - full image including all the disks.

* `aquariumApplicationTask(taskUid: 'UUID')` (`wait: false`)
   Allows to figure out the task info and result. This step doesn't want to be executed directly
   on the aquarium worker - so you can safely move on the pipeline without agent and wait there for
   your previous snapshot/image is created. Step requires just one parameter - is UUID string that
   you can get after execution of aquariumCreateSnapshot/aquariumCreateImage steps. Wait param will
   allow to block the pipeline until the result of the task become available.

* `aquariumApplicationInfo()`
   Returns info about the currently running node in which the step is executed. Useful if your
   pipeline wants to store it somewhere or use in the logic. The format is:
   ```yaml
   ApplicationInfo:
     ApplicationUID: "<UUID>"
     LabelName: "<name>"
     LabelVersion: <version>
   DefinitionInfo:  # See LabelDefinition in fish openapi yaml
   ```

## Implementation

The implementation is still PoC and not perfect in any way. For now it's mostly working.

Originally was based on [Scripted Cloud Plugin](https://plugins.jenkins.io/scripted-cloud-plugin/),
but later absorbed alot from [Kubernetes Plugin](https://plugins.jenkins.io/kubernetes/).
