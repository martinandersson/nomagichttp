[![GitHub CI](https://github.com/martinandersson/nomagichttp/actions/workflows/build.yml/badge.svg)](https://github.com/martinandersson/nomagichttp/actions/workflows/build.yml)
[![JitPack](https://jitpack.io/v/martinandersson/nomagichttp.svg)](https://jitpack.io/#martinandersson/nomagichttp)

# NoMagicHTTP

**A Java library for receiving HTTP requests and responding to them.**

The library uses virtual threads ([JEP 425][0-1]), meaning that the dependent
application writes "straightforward blocking code", yet reaps "near-optimal
hardware utilization".

The API is _elegant_, and based on the firmly held belief that magic is evil.
Reflection code, error-prone annotations, missing "beans" and God-like,
ill-defined "context" objects will never be a part of the library. The source
code is crafted by artsmen seeking perfection through simplicity, developer
happiness, and a minimal waste of time.

What you get is a server as fast and scalable as any cross-platform JDK-based
HTTP server implementation could possibly be.

[All-you-need JavaDoc is here.][0-2]

**WARNING:** This project is in an alpha phase without proper release
management/versioning. The document [POA.md][0-3] details planned future work,
and consequently, what parts of the HTTP stack have not yet been delivered.

[0-1]: https://openjdk.org/jeps/425
[0-2]: https://jitpack.io/com/github/martinandersson/nomagichttp/api/-SNAPSHOT/javadoc/alpha.nomagichttp/alpha/nomagichttp/HttpServer.html
[0-3]: POA.md

## Minimal Example

In an empty directory, create a new [Gradle][1-1] build file `build.gradle`:

```groovy
plugins {
    id('application')
}

repositories {
    maven {
        url('https://jitpack.io')
    }
}

dependencies {
    implementation('com.github.martinandersson:nomagichttp:master-SNAPSHOT')
}

application {
    mainClass = 'Greeter'
    // This is for the server implementation to work
    applicationDefaultJvmArgs = [
        '--enable-preview',
        '--add-modules', 'jdk.incubator.concurrent']
}
```

In subfolder `src/main/java`, create a new file `Greeter.java`:

```java
import alpha.nomagichttp.HttpServer;
import java.io.IOException;
import static alpha.nomagichttp.handler.RequestHandler.GET;
import static alpha.nomagichttp.message.Responses.text;

class Greeter {
    public static void main(String[] args) throws IOException, InterruptedException {
        HttpServer.create()
                  .add("/greeting",
                      GET().apply(request -> text("Hello Stranger!")))
                  .start(8080);
    }
}
```

Make sure you are using at least Gradle 8.2 and Java 20, then start the server:

```console
foo@bar:projectfolder$ gradle run
> Task :run
WARNING: Using incubator modules: jdk.incubator.concurrent
Jul 15, 2023 5:40:03 PM alpha.nomagichttp.core.DefaultServer lambda$openOrFail$8
INFO: Opened server channel: sun.nio.ch.ServerSocketChannelImpl[/[0:0:0:0:0:0:0:0]:8080]
<=========----> 75% EXECUTING [1s]
> :run
```

In another terminal:

```console
foo@bar:projectfolder$  curl localhost:8080/greeting
Hello Stranger!
```

In a real-world scenario where the Java runtime is used to start the
application, the start-up time is pretty much instantaneous. Be prepared for
uber-fast development cycles and running real HTTP exchanges in your test cases,
_because you can_ 🎉🙌.

[1-1]: https://docs.gradle.org/current/userguide/tutorial_using_tasks.html

## Getting Started

The NoMagicHTTP library is documented through detailed and exhaustive JavaDoc of
an API that is discoverable and intuitive. JavaDoc _is the contract_. Anything
one reads outside of JavaDoc, is _advisory_ only.

It is recommended to follow the links in each example as the source code
contains useful commentary that explains the API used.

The examples provided in subsequent sections, are packaged with the published
JAR and can be executed simply by replacing the `mainClass` value in the
previous Gradle build file.

To run the first example below, simply replace the class reference:

```groovy
application {
    mainClass = 'alpha.nomagichttp.examples.HelloWorld'
}
```

### Selecting port

If a port is not specified, the system will pick a port on the loopback address.

See code: [src/main/java/.../HelloWorld.java][3-1]

Run:

```console
foo@bar:projectfolder$ gradle run
Listening on port 40863.
```

Make a request to the port in a new terminal window:

```console
foo@bar:~$ curl -i localhost:40863/hello
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Content-Length: 12

Hello World!
```

[3-1]: api/src/main/java/alpha/nomagichttp/examples/HelloWorld.java

### Greet using name from request path

This example registers two routes in order to respond a greeting with a name
taken from a path- or query parameter.

See code: [src/main/java/.../GreetParameter.java][4-1]

Run:

```console
foo@bar:projectfolder$ gradle run
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:projectfolder$ curl -i localhost:8080/hello/John
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Content-Length: 11

Hello John!
```

Alternatively, you may pass the name as a query parameter:

```console
foo@bar:projectfolder$ curl -i localhost:8080/hello?name=John
```

[4-1]: api/src/main/java/alpha/nomagichttp/examples/GreetParameter.java

### Greet using name from request body

This example will greet the user with a name taken as being the request body.

See code: [src/main/java/.../GreetBody.java][5-1]

Run:

```console
foo@bar:projectfolder$ gradle run
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:projectfolder$ curl -i localhost:8080/hello -d John
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Content-Length: 11

Hello John!
```

[5-1]: api/src/main/java/alpha/nomagichttp/examples/GreetBody.java

### Echo request headers

This example echoes back the request headers.

See code: [src/main/java/.../EchoHeaders.java][6-1]

Run:

```console
foo@bar:projectfolder$ gradle run
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:projectfolder$ curl -i localhost:8080/echo \
    -H "My-Header: Value 1" \
    -H "My-Header: Value 2"
HTTP/1.1 204 No Content
Host: localhost:8080
User-Agent: curl/7.81.0
Accept: */*
My-Header: Value 1
My-Header: Value 2
```

[6-1]: api/src/main/java/alpha/nomagichttp/examples/EchoHeaders.java

### Keep client informed

A final response may be preceded by any number of interim 1XX (Informational)
responses. This is an excellent way to keep the client informed while processing
lengthy requests (without the need for server-sent events, web sockets, long
polling, et cetera).

See code: [src/main/java/.../KeepClientInformed.java][7-1]

Run:

```console
foo@bar:projectfolder$ gradle run
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:projectfolder$ curl -i localhost:8080
HTTP/1.1 102 Processing
Time-Left: 3 second(s)

HTTP/1.1 102 Processing
Time-Left: 2 second(s)

HTTP/1.1 102 Processing
Time-Left: 1 second(s)

HTTP/1.1 204 No Content
```

[7-1]: api/src/main/java/alpha/nomagichttp/examples/KeepClientInformed.java
