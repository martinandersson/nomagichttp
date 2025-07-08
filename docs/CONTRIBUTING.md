# Contributing

This document is an early draft. If you wish to become a contributor, please
reach out to; webmaster at martinandersson.com.

## Upgrading Gradle Wrapper

```bash
./gradlew wrapper --gradle-version X.Y --distribution-type all
./gradlew wrapper --gradle-version X.Y --distribution-type all
```

The first command only updates `gradle-wrapper.properties`. Quoting the
[Gradle docs]:

> If you want all the wrapper files to be completely up-to-date, you will need
> to run the wrapper task a second time.

These other files not updated the first time, are `gradlew`, `gradlew.bat` and
`gradle-wrapper.jar`.

Through experience, it was observed that running the second command without
any arguments effectively replaced the distribution type suffix in
`gradle-wrapper.properties` to the default `-bin`. This indicates that the
resulting wrapper binary is not the complete distribution.

One may also speculate that another effect could be that the resulting script
files are only up-to-date with the _current_ version of the Gradle Wrapper
invoking the task.

Thus it is safest to re-execute the same command with the same arguments, as
previously shown.

How on earth they managed to screw up such a simple task and still haven't
fixed the issue only God knows.

[Gradle docs]: https://docs.gradle.org/8.9/userguide/gradle_wrapper.html#sec:upgrading_wrapper
