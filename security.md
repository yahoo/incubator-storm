#Secure Multi-tenant Storm
At Yahoo We have been hard at work locking down storm so that it can be used as a secure hosted multi-tennant cluster.

We have added in Authentication using Kerberos or a java servlet filter, and Authorization to nimbus, DRPC, the UI, and logviewer processes.
We have set it up so that a topology can run as the user that launched it instead of as the headless user the supervisor runs as.
We locked down zookeeper and the local file system so that different topologies cannot get access to data they are not supposed to see.
We also implemented code to transparently push new credentials out to a running topology, so that it can have long term uninterrupted access to other secured resources like HDFS or HBase.

## Multitenant Storm Cluster Setup
It is assumed that you have already setup a working storm cluster.  We will walk you through securing that storm setup.

### Clean Up (Prep. For Security)

If you have started a non-security topology, there are some important steps to prepare for enabling security features.

#### Kill Topologies
```bash
# For all topologies...
storm kill "$TOPO_NAME"
```
#### Stop Services
Depending on how you have your deamons running under supervison this may change.  But it is likely something like
```bash
svc -d storm_nimbus
svc -d storm_ui
svc -d storm_supervisor
svc -d storm_logviewer
svc -d zookeeper
```

#### Clear Storm Daemon State

On each node, clear any state for nimbus, supervisors, workers, etc.  This prevents permission errors when running the various storm daemons as a different user later.

```bash
rm -rf ${storm.local.dir}/*
```

Also, from zookeeper nodes clear state using:
```bash
rm -rf ${dataDir}/*
```

### Authentication Setup

It is assumed that you already have access to a KDC or know how to set one up yourself.  We will not go into details of how to do this.  Be sure that all nodes in the cluster will have access to the kdc.

#### Create Headless Principals and keytabs
Each Zookeeper Server, Nimbus, and DRPC server will need a service principal, which, by convention, includes the FQDN of the host it will run on.  Be aware that the zookeeper user *MUST* be zookeeper.  
The supervisors and UI also need a principal to run as, but because they are outgoing connections they do not need to be service principals.  

```bash
# Zookeeper (Will need one of these for each box in teh Zk ensamble)
sudo /usr/sbin/kadmin.local -q 'addprinc zookeeper/zk1.example.com@STORM.EXAMPLE.COM'
sudo /usr/sbin/kadmin.local -q "ktadd -k /tmp/zk.keytab  zookeeper/zk1.example.com@STORM.EXAMPLE.COM"
# Nimbus
sudo /usr/sbin/kadmin.local -q 'addprinc storm/storm.example.com@STORM.EXAMPLE.COM'
sudo /usr/sbin/kadmin.local -q "ktadd -k /tmp/storm.keytab storm/storm.example.com@STORM.EXAMPLE.COM"
# All UI and Supervisors
sudo /usr/sbin/kadmin.local -q 'addprinc storm@STORM.EXAMPLE.COM'
sudo /usr/sbin/kadmin.local -q "ktadd -k /tmp/storm.keytab storm@STORM.EXAMPLE.COM"
```

be sure to distribute the keytab to the appropriate boxes and set the FS permissions so that only the headless user running ZK, or storm has access to them.

#### JAAS Configuration

To enable Kerberos authentication in storm you need to set the following storm.yaml configs
```yaml
storm.thrift.transport: "backtype.storm.security.auth.kerberos.KerberosSaslTransportPlugin"
java.security.auth.login.config: "/path/to/jaas.conf"
```

Nimbus and the supervisor processes will also connect to ZooKeeper(ZK) and we want to configure them to use Kerberos for authentication with ZK. To do this append 
```
-Djava.security.auth.login.config=/path/to/jaas.conf
```

to the childopts of nimbus, ui, and supervisor.  Here is an example given the default childopts settings at the time of writing:

```yaml
nimbus.childopts: "-Xmx1024m -Djava.security.auth.login.config=/path/to/jaas.conf"
ui.childopts: "-Xmx768m -Djava.security.auth.login.config=/path/to/jaas.conf"
supervisor.childopts: "-Xmx256m -Djava.security.auth.login.config=/path/to/jaas.conf"
```

The jaas.conf file should look something like the following for the storm nodes.
The StormServer section is used by Nimbus and the DRPC Nodes.  It does not need to be included on supervisor nodes.
The StormClient section is used by all storm clients that want to talk to nimbus, including the ui, logviewer, and supervisor.  We will use this section on the gateways as well but the structure of that will be a bit different.
The Client section is used by processes wanting to talk to zookeeper and really only needs to be included with Nimbus and the supervisors.
The Server section is used by the zookeeper servers.
Having unused sections in the jaas is not a problem.

```
StormServer {
   com.sun.security.auth.module.Krb5LoginModule required
   useKeyTab=true
   keyTab="$keytab"
   storeKey=true
   useTicketCache=false
   principal="$principal";
};
StormClient {
   com.sun.security.auth.module.Krb5LoginModule required
   useKeyTab=true
   keyTab="$keytab"
   storeKey=true
   useTicketCache=false
   serviceName="$nimbus_user"
   principal="$principal";
};
Client {
   com.sun.security.auth.module.Krb5LoginModule required
   useKeyTab=true
   keyTab="$keytab"
   storeKey=true
   useTicketCache=false
   serviceName="zookeeper"
   principal="$principal";
};
Server {
   com.sun.security.auth.module.Krb5LoginModule required
   useKeyTab=true
   keyTab="$keytab"
   storeKey=true
   useTicketCache=false
   principal="$principal";
};
```

The following is an example based off of the keytabs generated
```
StormServer {
   com.sun.security.auth.module.Krb5LoginModule required
   useKeyTab=true
   keyTab="/keytabs/storm.keytab"
   storeKey=true
   useTicketCache=false
   principal="storm/storm.example.com@STORM.EXAMPLE.COM";
};
StormClient {
   com.sun.security.auth.module.Krb5LoginModule required
   useKeyTab=true
   keyTab="/keytabs/storm.keytab"
   storeKey=true
   useTicketCache=false
   serviceName="storm"
   principal="storm@STORM.EXAMPLE.COM";
};
Client {
   com.sun.security.auth.module.Krb5LoginModule required
   useKeyTab=true
   keyTab="/keytabs/storm.keytab"
   storeKey=true
   useTicketCache=false
   serviceName="zookeeper"
   principal="storm@STORM.EXAMPLE.COM";
};
Server {
   com.sun.security.auth.module.Krb5LoginModule required
   useKeyTab=true
   keyTab="/keytabs/zk.keytab"
   storeKey=true
   useTicketCache=false
   serviceName="zookeeper"
   principal="zookeeper/zk1.example.com@STORM.EXAMPLE.COM";
};
```

Nimbus also will translate the principal into a local user name, so that other services can use this name.  To configure this for Kerberos authentication set

```
storm.principal.tolocal: "backtype.storm.security.auth.KerberosPrincipalToLocal"
```

This only needs to be done on nimbus, but it will not hurt on any node.
We also need to inform the topology who the supervisor daemon and the nimbus daemon are running as from a ZooKeeper perspective.

```
storm.zookeeper.superACL: "sasl:${nimbus-user}"
```

Here *nimbus-user* is Kerberos user that nimbus uses to authenticate with ZooKeeper.  If ZooKeeeper is stripping host and realm then this needs to have host and realm stripped too.

#### ZooKeeper Ensemble

Complete details of how to setup a secure ZK are beyond the scope of this document.  But in general you want to enable SASL authentication on each server, and optionally strip of host and realm

```
authProvider.1 = org.apache.zookeeper.server.auth.SASLAuthenticationProvider
kerberos.removeHostFromPrincipal = true
kerberos.removeRealmFromPrincipal = true
```

And you want to include the jaas.conf on the command line when launching the server so it can use it can find the keytab.
```
-Djava.security.auth.login.config=/jaas/zk_jaas.conf
```

#### Gateways
Ideally the end user will only need to run kinit before interacting with storm.  To make this happen seamlessly we need the default jaas.conf on the gateways to be something like

```
StormClient {
   com.sun.security.auth.module.Krb5LoginModule required
   doNotPrompt=false
   useTicketCache=true
   serviceName="$nimbus_user";
};
```

The end user can override this if they have a headless user that has a keytab.

### Authorization Setup

*Authentication* does the job of verifying who the user is, but we also need *authorization* to do the job of enforcing what each user can do.

The preferred authorization plug-in for nimbus is The *SimpleACLAuthorizer*.  To use the *SimpleACLAuthorizer*, set the following:

```yaml
nimbus.authorizer: "backtype.storm.security.auth.authorizer.SimpleACLAuthorizer"
```

DRPC has a separate authorizer configuration for it.  Do not use SimpleACLAuthorizer for DRPC.

The *SimpleACLAuthorizer* plug-in needs to know who the supervisor users are, and it needs to know about all of the administrator users, including the user running the ui daemon. 

These are set through *nimbus.supervisor.users* and *nimbus.admins* respectively.  Each can either be a full Kerberos principal name, or the name of the user with host and realm stripped off.

The UI and Log servers have their own authorization configurations.  These are set through *logs.users* and *ui.users*.  These should be set to the admin users for all of the nodes in the cluster.  

When a topology is sumbitted, the sumbitting user can specify users in this list as well.  The users specified-in addition to the users in the cluster-wide setting-will be granted access to the submitted topology's details in the ui and/or to the topology's worker logs in the logviewers.  

### Supervisors headless User and group Setup

To ensure isolation of users in multi-tenancy, there is need to run supervisors and headless user and group unique to execution on the supervisor nodes.  To enable this follow below steps.
1. Add headlessuser to all supervisor hosts.
2. Create unique group and make it the primary group for the headless user on the supervisor nodes.
3. The set following properties on storm for these supervisor nodes.

### Multi-tenant Scheduler

To support multi-tenancy better we have written a new scheduler.  To enable this scheduler set.
```yaml
storm.scheduler: "backtype.storm.scheduler.multitenant.MultitenantScheduler"
```
Be aware that many of the features of this scheduler rely on storm authentication.  Without them the scheduler will not know what the user is and will not isolate topologies properly.

The goal of the multi-tenant scheduler is to provide a way to isolate topologies from one another, but to also limit the resources that an individual user can have in the cluster.

The scheduler currently has one config that can be set either through =storm.yaml= or through a separate config file called =multitenant-scheduler.yaml= that should be placed in the same directory as =storm.yaml=.  It is preferable to use =multitenant-scheduler.yaml= because it can be updated without needing to restart nimbus.

There is currently only one config in =multitenant-scheduler.yaml=, =multitenant.scheduler.user.pools= is a map from the user name, to the maximum number of nodes that user is guaranteed to be able to use for their topologies.

For example:

```yaml
multitenant.scheduler.user.pools: 
    "evans": 10
    "derek": 10
```

### UI Authentication
We have not written a default SPNEGO plugin for storm yet.  If you have a java servlet filter that will authenticate the user you can configure it using.

```yaml
ui.filter: "filter.class"
ui.filter.params: "param1":"value1"
```

### Run as User
By default storm runs workers as the user that is running the supervisor.  This is not ideal for security.  To make storm run the topologies as the user that launched them set.

```yaml
supervisor.run.worker.as.user: true
```

There are several files that go along with this that are needed to be configured properly to make storm secure.

The worker-launcher executable is a special program that allows the supervisor to launch workers as different users.  For this to work it needs to be owned by root, but with the group set to be a group that only teh supervisor headless user is a part of.
It also needs to have 6550 permissions.
There is also a worker-launcher.cfg file, usually located under /etc/ that should look somethign like the following

```
storm.worker-launcher.group=$(worker_launcher_group)
min.user.id=$(min_user_id)
```
where worker_launcher_group is the same group the supervisor is a part of, and min.user.id is set to the first real user id on the system.
This config file also needs to be owned by root and not have world or group write permissions.

### Automatic Credentials Push and Renewal
Individual topologies have the ability to push credentials (tickets and tokens) to workers so that they can access secure services.  Exposing this to all of the users can be a pain for them.
To hide this from them in the common case plugins can be used to populate the credentials, unpack them on the other side into a java Subject, and also allow Nimbus to renew the credentials if needed.
These are controlled by the following configs. topology.auto-credentials is a list of java plugins that populate the credentials and unpack them on the worker side.
On a kerberos secure cluster they should be set by default to point to backtype.storm.security.auth.kerberos.AutoTGT.  nimbus.credential.renewers.classes should also be set to this value so that nimbus can periodically renew the TGT on behalf of the user.

nimbus.credential.renewers.freq.secs controls how often the renewer will poll to see if anything needs to be renewed, but the default should be fine.

### Limits
By default storm allows any sized topology to be submitted. But ZK and others have limitations on how big a topology can actually be.  The following configs allow you to limit the maximum size a topology can be.

| YAML Setting | Description |
|------------|----------------------|
| nimbus.slots.perTopology | The maximum number of slots/workers a topology can use. |
| nimbus.executors.perTopology | The maximum number of executors/threads a topology can use. |

### Log Cleanup
The Logviewer deamon now is also responsible for cleaning up old log files for dead topologies.

| YAML Setting | Description |
|--------------|-------------------------------------|
| logviewer.cleanup.age.mins | How old (by last modification time) must a worker's log be before that log is considered for clean-up. (Living workers' logs are never cleaned up by the logviewer: Their logs are rolled via logback.) |
| logviewer.cleanup.interval.secs | Interval of time in seconds that the logviewer cleans up worker logs. |


### DRPC
Hopefully more on this soon

