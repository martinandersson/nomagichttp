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

Each of the following examples has a link to the source code which should be
read as it contains helpful code commentary to introduce the NoMagicHTTP API.

The examples assume that Java 11+ is installed and the current working
directory is the NoMagicHTTP project root. In addition, please run these
commands before trying the examples:

```shell
./gradlew build
JAR=build/libs/nomagichttp.jar
PKG=alpha.nomagichttp.examples
```

[1-1]: src/main/java/alpha/nomagichttp/package-info.java
[1-2]: https://docs.oracle.com/en/java/javase/12/tools/java.html#GUID-3B1CE181-CD30-4178-9602-230B800D4FAE__USINGSOURCE-FILEMODETOLAUNCHSINGLE--B5E57618

### Hello World

This example will make the server respond with a static "Hello World!" message.

See code: [src/main/java/.../HelloWorld.java][2-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.HelloWorld
Listening on port 52063.
```

Make a request to the port in a new terminal window:

```console
foo@bar:~$ curl -i localhost:52063/hello
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Content-Length: 12

Hello World!
```

[2-1]: src/main/java/alpha/nomagichttp/examples/HelloWorld.java

### Greet using name from request path

This example registers two routes in order to respond a greeting with a name
taken from a path- or query parameter.

See code: [src/main/java/.../GreetParameter.java][3-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.GreetParameter
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:~$ curl -i localhost:8080/hello/John
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Content-Length: 11

Hello John!
```

Alternatively, you may pass the name as a query parameter:

```console
foo@bar:~$ curl -i localhost:8080/hello?name=John
```

[3-1]: src/main/java/alpha/nomagichttp/examples/GreetParameter.java

### Greet using name from request body

This example will greet the user with a name taken as being the request body.

See code: [src/main/java/.../GreetBody.java][4-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.GreetBody
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:~$ curl -i localhost:8080/hello -d John
HTTP/1.1 200 OK
Content-Type: text/plain; charset=utf-8
Content-Length: 12

Hello, John!
```

[4-1]: src/main/java/alpha/nomagichttp/examples/GreetBody.java

### Echo request headers

This example echoes back the request headers.

See code: [src/main/java/.../EchoHeaders.java][5-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.EchoHeaders
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:~$ curl -i localhost:8080/echo \
    -H "My-Header: Value 1" \
    -H "My-Header: Value 2"
HTTP/1.1 200 OK
Accept: */*
Host: localhost:8080
My-Header: Value 1
My-Header: Value 2
User-Agent: curl/7.68.0
Content-Length: 0
```

[5-1]: src/main/java/alpha/nomagichttp/examples/EchoHeaders.java

### Retry request on error

This example demonstrates error handling and will re-execute the request handler
on a particular known exception.

See code: [src/main/java/.../RetryRequestOnError.java][6-1]

Run:

```console
foo@bar:~$ java --class-path=$JAR $PKG.RetryRequestOnError
Listening on port 8080.
```

In a new terminal, run:

```console
foo@bar:~$ curl -i localhost:8080
HTTP/1.1 200 OK
Content-Length: 0
```

In the server terminal, you should see text similar to this:
```console
Request handler received a request 15:19:58.780 and will crash!
Error handler will retry #1 after delay (ms): 40
Request handler received a request 15:19:58.827 and will return 200 OK
```

[6-1]: src/main/java/alpha/nomagichttp/examples/RetryRequestOnError.java
