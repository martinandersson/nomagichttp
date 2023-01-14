# Contributing

This document is an early draft. If you wish to become a contributor, please
reach out to me; webmaster at martinandersson.com.

## Updating the Gradle Wrapper

```bash
./gradlew wrapper --gradle-version X.Y --distribution-type all
./gradlew wrapper
```

The first command only updates `gradle-wrapper.properties`. To quote the
[Gradle docs], if you "nevertheless want **all** the wrapper files to be
completely up-to-date, you’ll need to run the wrapper task a second time".

These other files are `gradlew`, `gradlew.bat` and `gradle-wrapper.jar`.

[Gradle docs]: https://docs.gradle.org/7.6/userguide/gradle_wrapper.html#sec:upgrading_wrapper