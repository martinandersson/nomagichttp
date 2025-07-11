# Contributing

This document is an early draft. If you wish to become a contributor, please
reach out to; webmaster at martinandersson.com.

## Upgrading Gradle Wrapper

### First prepare

It is strongly recommended to read the [release notes][FP-1], especially the
migration guide, which is essential when upgrading to a new major version.

Any preparation work that should be done before upgrading, should be done before
upgrading 😂

All work should be done on a new branch.

[FP-1]: https://gradle.org/releases

### Execute two commands

```bash
./gradlew wrapper --gradle-version X.Y --distribution-type all
./gradlew wrapper --gradle-version X.Y --distribution-type all
```

The first one allegedly updates only `gradle-wrapper.properties`.

Quoting the [Gradle docs][ETC-1]:

> Note that running the wrapper task once will update gradle-wrapper.properties
> only[.]

Which may be true most of the time, but updating from v8.13 to v8.14.3 changed —
in addition to the properties file — also `gradlew` and `gradle-wrapper.jar`.

> If you want all the wrapper files to be completely up-to-date, you will need
> to run the wrapper task a second time.

These other files which may not have been updated the first time; may be none,
or any combination of `gradlew`, `gradlew.bat`, and `gradle-wrapper.jar`.

The second command going from v8.13 to v8.14.3 changed all of the previously
listed files, yes two of them for a second time with some more changes 🤯

Another observation is that running the second command without any arguments
replaces the distribution type suffix in `gradle-wrapper.properties` to the
default "-bin".

The moral of the story is the following: for a complete and consistent
update, please re-execute the same command twice and with the same arguments 🤐

[ETC-1]: https://docs.gradle.org/8.14.3/userguide/gradle_wrapper.html#sec:upgrading_wrapper

### Fix failing build issues, commit

Any build issues failing the build must be fixed, probably in separate commits.

Then commit the changes to all Wrapper-files with a message:

> Upgrade/Update Gradle Wrapper from A.B to C.D

💡 We _upgrade_ when the major version was bumped, but we'll most of the time
_update_ when the minor and/or patch version changes.

### New build warnings

...should be fixed, otherwise documented as a build issue in [BUILD.md][NBW-1].

[NBW-1]: BUILD.md

### Fix legacy build issues

Some of the issues already listed in BUILD.md may now be fixable 🤞

For remaining unfixed issues and similar build commentary elsewhere, make sure
that links to Gradle's documentation with an embedded version number in the
path, are updated to the new version.

If applicable, also update relevant text. For example, a quote may have changed.

This subtle detail is an implicit confirmation to the reader that the issue
still persists and was not accidentally overlooked.

💡 Text-search all project files with the previous version number.

### Migrate to new features

If and only if, they yield an improvement in code readability.

Finally, the work branch is ready to be merged 🥳

## Upgrading Java

Execute (just once is enough):

```bash
./gradlew updateDaemonJvm --jvm-version=X --jvm-vendor=adoptium
```

Before committing the updated [`./gradle/gradle-daemon-jvm.properties`][UJ-1]
file, make sure that the project still builds.

It's a bit of a pain to learn what JDKs are available via the resolver. For now,
one will have do trial and error.

See this [GitHub issue][UJ-2].

[UJ-1]: ../gradle/gradle-daemon-jvm.properties
[UJ-2]: https://github.com/gradle/FOOJAY-toolchains/issues/50
