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

The first one only updates `gradle-wrapper.properties`.

Quoting the [Gradle docs][ETC-1]:

> If you want all the wrapper files to be completely up-to-date, you will need
> to run the wrapper task a second time.

Starting with Gradle 8.10, the only other file updated when invoking the command
a second time, is `gradle-wrapper.jar`.

Through experience, it was observed that running the second command without
any arguments replaced the distribution type suffix in
`gradle-wrapper.properties` to the default `-bin`. This indicates that the
resulting wrapper binary is not the complete distribution.

Thus it is safest to re-execute the same command with the same arguments, as
previously shown.

How on earth they managed to screw up such a simple task and still haven't
fixed the issue only God knows.

[ETC-1]: https://docs.gradle.org/8.10/userguide/gradle_wrapper.html#sec:upgrading_wrapper

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

If applicable, also update relevant text. For example, a quote changed.

This subtle detail is an implicit confirmation to the reader that the issue
still persists and was not accidentally overlooked.

💡 Text-search all project files with the previous version number.

### Migrate to new features

If and only if, they yield an improvement in code readability.

Finally, the work branch is ready to be merged 🥳
