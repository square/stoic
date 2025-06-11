# Stoic Architecture

Stoic is in the early stages of development. This architectural doc describes
the vision of the future of Stoic. Not all of it is implemented yet.

At its core, Stoic is flexible interprocess communication (IPC) and remote
procedure calls (RPC). Stoic is a debugging tool. It trades off security in
favor of flexibility. Don't run it in production.

Instead of requiring pre-established IPC/RPC servers, Stoic can use debugging
APIs (JVMTI, maybe ptrace in the future) to inject code and establish a server
in existing debuggable processes. Instead of calling existing functions like
traditional IPC/RPC, Stoic can send code over to be run.

Functions are not limited to single input / single output. Stoic provides
multiplexed bidirectional communication. Stoic can provide a traditional
command-line-interface (CLI) style stdin/stdout/stderr, and/or you can use the
pseudo-file-descriptor functionality to multiplex your own packets/streams. 

Stoic provides in-process access to debugging APIs (JVMTI) so you can do things
like get callbacks or iterate over objects in the heap.

```
                  Process / Machine
                      boundary
+------------+           |           +--------------+
|            |           |           |  target      |
|            |           |           |              |
| +--------+ | --------- | --------> | +--------+   |
| | client | | <-------- | --------- | | server |   |
| +--------+ |           |           | +--------+   |
|            |           |           |              |
+------------+           |           +--------------+
```

The stoic "client" is the thing that kicks off the RPC. The stoic "target" is the
thing the RPC runs within. In order to provide RPC functionality, Stoic depends on:
1. An injection mechanism (typically JVMTI)
2. A bidirectional transport (typically Unix Domain Sockets in conjunction with `adb shell`)

## Steps in running a plugin

With the aforementioned dependencies in place, Stoic will:
1. Inject its server inside the target
2. The target will then notify the client that it's ready for connection
3. The client will then connect to the server and send it a StartPlugin request
   containing the name and timestamp of the code to be run.
4. The target will then, assuming it doesn't have the plugin already, respond
   with a GetPlugin request.
5. The client will send a response consisting of the code to be run (typically
   in JAR) form.
6. The client will also immediately begin sending any input the function to be
   run expects (e.g. array of Strings)
7. The server will begin running the RPC. If the RPC expects input parameters
   (e.g. array of Strings), it can block waiting for them. There is nothing
   special about this input, so from the perspective of Stoic it's handled the
   same way as streaming IO.
8. The client and server will exchange multiplexed IO - i.e.
   stdin/stdout/stderr or some mutually agreed upon protocol.
9. When the plugin finishes (either normally or abnormally) it sends a
   PluginFinished packet and the connection terminates.
10. The Stoic server will continue to service connection requests in the
    target, making future connections faster.

Stoic provides the mechanisms for each to dynamically allocate/deallocate
pseudo-file-descriptors. Or they can use mutually agreed upon statically
allocated pseudo-file-descriptors.

## Configuring Stoic's dependencies

Stoic's architecture is designed to be flexible. You can add additional
methods of injecting code and/or communicating bidirectionally into the Stoic
source code, and use them to run new forms of plugins in new places.

Instead of injection via JVMTI, we could inject via ptrace. Or we could compile
the stoic server into a non-debuggable app. Or we could host a stoic server via
Javascript in the browser. Instead of running JVM-based code, we could run
native code or Javascript.

With additional effort, Stoic could support running plugins:
1. On your laptop
2. In native processes
3. In traditional JVM processes (non-ART)
4. In the browser
5. On remote systems (via SSH)
6. In iOS apps

## Injection

Stoic currently injects code into Android apps via JVMTI, using the
`attach-agent` mechanism documented in
https://source.android.com/docs/core/runtime/art-ti. Though Android heavily
restricts communication with package processes, Stoic can usually work even on
non-rooted devices through careful use of `run-as`. For example, Stoic copies
its jvmti agent over to the package directory with:
```
cat stoic-jvmti.so | run-as com.example sh -c 'cat > stoic/stoic-jvmti.so'
```

## Bidirectional communication

Stoic establishes bidirectional communication via Unix Domain Sockets. As with
injection, Stoic makes careful use of `run-as` to allow it to work on
non-rooted devices.

Android will not allow the shell user to directly connect to a Unix Domain
Socket owned by a package (or vice versa), so instead Stoic connects with:
```
run-as com.example socat - ABSTRACT-CONNECT://...
```

When connecting from your laptop, stoic uses a fast-path - forwarding a port to
the unix domain socket. This works without any `run-as` tricks. This way we can
connect directly to the server without going through `adb shell`.
```
adb forward tcp:0 localabstract:/stoic/...
```
