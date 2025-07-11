# Build

This document describes the project's components and build system.

## Subprojects

### buildSrc

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
JavaDoc is meant for internal library developers, if anyone. The primary purpose
is linting 👍

### API

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

### Core

Contains the `DefaultServer` together with related classes.

### Test Util

Is a collection of utils for test source sets. This project also pulls in
transitive dependencies common for all test source sets, but limits the
visibility of `alpha.nomagichttp.testutil.functional` to core's medium and large
tests.

### Reports

Is a namespace for tasks that aggregate Gradle test reports and Jacoco coverage
reports from all subprojects.

There should be no need to invoke reporting tasks explicitly. Tasks `test`,
`mediumTest` and `largeTest`, is each finalized by aggregating the reports into
`./reports/build/gradle` and `./reports/build/jacoco` respectively.

## Issues

### Non-colocated dependency specifications

The Foojay resolver plugin (a JDK resolver) is applied in
[./settings.gradle][NCDS-1] together with the ID and version.

The ID and version should be specified in
[./gradle/libs.versions.toml][NCDS-2] (together with all the rest of the
project's dependency coordinates and versions).

Alas Gradle does not support this: the version catalog is inaccessible from the
settings.gradle file. The _technical cause_ is that settings.gradle is evaluated
before the version catalog.

For now, this seems to be dismissed by Gradle as a "chicken and egg" problem
(verbiage from user guide v8.10), out of sheer convenience: they rather float a
half-baked version catalog feature coupled with unexplained warning boxes all
over the documentation notifying developers to not reference the version catalog
in a settings.gradle file, rather than to hack at Gradle's life-cycle.

See [Gradle's user guide][NCDS-3], a [blog post][NCDS-4] and a
[GitHub issue][NCDS-5].

[NCDS-1]: ../settings.gradle
[NCDS-2]: ../gradle/libs.versions.toml
[NCDS-3]: https://docs.gradle.org/8.14.3/userguide/version_catalogs.html#sec:plugins-ver
[NCDS-4]: https://melix.github.io/blog/2021/03/version-catalogs-faq.html#_can_i_use_a_version_catalog_to_declare_plugin_versions
[NCDS-5]: https://github.com/gradle/gradle/issues/24876

### Used feature previews

[./settings.gradle][UFP-1] has this:

    enableFeaturePreview('TYPESAFE_PROJECT_ACCESSORS')

To be removed when said feature is promoted to public.

[UFP-1]: ../settings.gradle

### Clumsy project accessor

[./reports/build.gradle][CPA-1] configures the test tasks in `core` to be
finalized by the `reports` aggregation tasks.

Traversing to `core`'s tasks used to be done like this:

    projects.core.dependencyProject.tasks.named("e.g. mediumTest") ...

`projects.core` is a "type-safe project accessor", but contrary to what the
feature name suggests; what is referenced is not the `Project` object, but
rather a `ProjectDependency` thing. And so the code must traverse further
using `getDependencyProject()`.

Gradle doesn't like this idiom and starting from v8.11, they deprecated
`getDependencyProject()`. Instead they added `getPath()`.

So the code was replaced with:

    project(projects.core.path).tasks.named("e.g. mediumTest") ...

This is arguably just as butt-ugly as the previous version, but it is
nonetheless Gradle's endorsed replacement.

One alternative would be to not use project accessors:

    project(':core').tasks.named("e.g. mediumTest") ...

To be consistent with other parts of the build using project accessors, and to
be forward-compatible (string literals are being phased out?), means that we
don't really have an option.

It is noted here as a build issue, because there may be a better way to
accomplish the same configuration. Or perhaps Gradle will evolve project
accessors further. It does seem fragile that Gradle deprecates a method based on
an aversion against what the method is used for, only to provide a new method as
a direct replacement to accomplish the same thing.

See [GitHub issue][CPA-2], a [discussion][CPA-3], and [Gradle's user guide][CPA-4].

💡 One option may be to set up the task relationships from the root, but for
now we'd rather have all configuration related to `reports` in said project
(cohesion) 🤔

[CPA-1]: ../reports/build.gradle
[CPA-2]: https://github.com/gradle/gradle/issues/30992
[CPA-3]: https://discuss.gradle.org/t/very-long-syntax-when-using-type-safe-project-accessors/39423
[CPA-4]: https://docs.gradle.org/8.14.3/userguide/declaring_dependencies_basics.html#sec:type-safe-project-accessors

### Explicit buildSrc name

Currently, [./buildSrc/settings.gradle][EBN-1] consists of only one line:

    rootProject.name = 'buildSrc'

Without it, Gradle yields a warning:

> Project accessors enabled, but root project name not explicitly set for
> 'buildSrc'. Checking out the project in different folders will impact the
> generated code and implicitly the buildscript classpath, breaking caching.

One should note that no other subprojects have a `settings.gradle` file: the
name is the folder name (and there's nothing to warn about it).

The _technical cause_ behind the warning may be that Gradle treats the buildSrc
as an included build, not as a subproject. Then, some random internal
check-algorithm is triggered and spits the warning, because, quoting
[Gradle's user guide][EBN-2]:

> [...] included builds can be located anywhere on disk, their build path is
> determined by the name of the containing directory. This can sometimes lead to
> conflicts.

Well, buildSrc is magic sauce provided by Gradle to specifically share build
logic _across a multi-project_. It is absolutely not, ever, supposed to be
included outside of the multi-project.

Therefore the warning is moot, and explicitly setting the name as we do, is
merely a method to suppress it.

The fact that Gradle out of the box yields a nonsense warning when developers
follow their own convention, is very sad. Hopefully one day, Gradle will program
an exception into the algorithm, or treat buildSrc as a quote unquote "hidden
subproject", whatever.

[EBN-1]: ../buildSrc/settings.gradle
[EBN-2]: https://docs.gradle.org/8.14.3/userguide/composite_builds.html#included_builds

## Improvements

### Verify Gradle Wrapper integrity

Quoting [Gradle's user guide][VGWI-1]:

> Since the Wrapper JAR is usually checked into a project’s version control
> system, there is the potential for a malicious actor to replace the original
> JAR with a modified one by submitting a pull request that only upgrades the
> Gradle version.

Same page speaks of a GitHub Action, but we'd like to have this done locally as
well.

[VGWI-1]: https://docs.gradle.org/8.14.3/userguide/gradle_wrapper.html#wrapper_checksum_verification
