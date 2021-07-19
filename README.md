# Aquarium Net Jenkins

Aquarium resources manager jenkins plugin to allocate required resources on demand.

## Requirements

* Jenkins >= 2.164.3

## Build

To build just download the latest maven from apache and run `mvn clean package` - it will create the
`./target/aquarium-net-jenkins.hpi` file which you can use to install in Jenkins.

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

## Implementation

Is based on [Scripted Cloud Plugin](https://plugins.jenkins.io/scripted-cloud-plugin/) with a number
of important changes for dynamic provision:

* The agent nodes are created by the plugin, based on the cluster label information.
