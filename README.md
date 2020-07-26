# NoMagicHTTP

NoMagicHTTP is an asynchronous server-side Java library used to receive HTTP
requests and respond to them.

The NoMagicHTTP library strives to offer an elegant and powerful API that is
just about as fast and scalable as any fully JDK-based HTTP server
implementation could possibly be.

Best of all, this library is designed around the firmly held opinion that all
forms of magic are evil. Annotations and "beans" will never be a part of the
library, only developer joy and productivity.

**WARNING: This project is fresh out of the oven and probably not very useful at
the moment. Please become an early contributor and join the fight to rid the
world of magic!**

## Getting started

The intent of this project is to be primarily documented through javadoc of an
API that is _discoverable_ and intuitive. A good start to read about core Java
types and the architecture is the [package-info.java][1-1] file of
`alpha.nomagichttp`.

The following examples assume that Java 11+ is installed and the current working
directory is the NoMagicHTTP project root. In addition, please run these
commands:

```shell
./gradlew build
JAR=build/libs/nomagichttp.jar
PKG=alpha.nomagichttp.examples
```

[1-1]: src/main/java/alpha/nomagichttp/package-info.java
[1-2]: https://docs.oracle.com/en/java/javase/12/tools/java.html#GUID-3B1CE181-CD30-4178-9602-230B800D4FAE__USINGSOURCE-FILEMODETOLAUNCHSINGLE--B5E57618

### Hello World - Console

This example will make the server print "Hello, World!" in the console.

See code: [src/main/java/.../HelloWorldConsole.java][2-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.HelloWorldConsole
Listening on port 52063.
```

Take note of the port and in a new terminal, run:

```console
foo@bar:~$ curl -i localhost:52063
HTTP/1.1 202 Accepted
Content-Length: 0
```

[2-1]: src/main/java/alpha/nomagichttp/examples/HelloWorldConsole.java

### Hello World - Response

This example will make the server respond with "Hello, World!" in the message
body.

See code: [src/main/java/.../HelloWorldResponse.java][3-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.HelloWorldResponse
Listening on port 8080.
```

Unlike the previous example, this version has hardcoded port 8080.

Run:

```console
foo@bar:~$ curl -i localhost:8080
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Content-Length: 13

Hello, World!
```

[3-1]: src/main/java/alpha/nomagichttp/examples/HelloWorldResponse.java