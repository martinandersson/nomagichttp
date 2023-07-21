# Build

This document describes the build system and modules.

## buildSrc

Contains Gradle plugins. In here is where one finds most of the build system
configuration.

Notably, each Java project builds two kinds of JavaDoc.

There's the regular `javadoc` task which builds JavaDoc HTML from symbols with
protected and higher visibility, in the `main` source set. The result is
available in `{subproject}/build/docs/javadoc`. This JavaDoc is meant for
application developers using the NoMagicHTTP library.

Then there's the `javadocAll` task, which builds JavaDoc HTML from symbols with
private and higher visibility, from all source sets; `main` and test source
sets. The result is available in `{subproject}/build/docs/javadoc-all`. This
JavaDoc is meant for internal library developers, if anyone. The purpose is
primarily linting 👍

## API

This module is the public API used by application developers. It is the home of
interfaces such as `HttpServer`, `Request` and `Response`.

The API module also contains classes/implementations. For example, both the
interface `EventHub` and class `DefaultEventHub` resides in package
`alpha.nomagichttp.event`. Another example is the package
`alpha.nomagichttp.util`, which contains mostly classes.

The intent of the packages in the API module is to publicly contain everything
the application may need, without having to import symbols from "core" packages.
This is often a big problem when developing with other libraries; one needs to
google what pieces belong together and what are all the dependencies one needs
to put on the classpath to finally get the job done. If not obvious already, the
NoMagicHTTP library is all about simplicity and developer happiness.

Interfaces often create instances of the implementation, and so far, only the
interface `HttpServer` uses Java's service loader mechanism to create an
instance from the Core module. The architecture does not support the use of
custom implementations provided by the application.

Nonetheless, the architecture will be revised. If it can be done in a clean and
elegant way, without being intrusive, for example by using Java's service loader
mechanism, then it wouldn't hurt if all implementations moved to the Core
module. Potentially, we could also support custom implementations from the
application.

## Core

Contains the `DefaultServer` together with related classes.

## Test Util

Is a collection of utils for test source sets. This project also pulls in
transitively dependencies common for all test source sets, but limits the
visibility of `alpha.nomagichttp.testutil.functional` to core's medium and large
tests.

## Reports

Is a namespace for tasks that aggregate Gradle test reports and Jacoco coverage
reports from all subprojects.

There should be no need to invoke reporting tasks explicitly. Tasks `test`,
`mediumTest` and `largeTest`, is each finalized by aggregating the reports into
`./reports/build/gradle` and `./reports/build/jacoco` respectively.
