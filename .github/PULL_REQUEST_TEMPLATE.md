**What this changes, and why**

**How it was checked**
- [ ] `./gradlew :shared:jvmTest :server:test :app:testDebugUnitTest`
- [ ] `./gradlew :app:lintDebug` still reports 0 errors
- [ ] New user-visible strings are in `values/`, `values-de/` and `values-ru/`, same keys in the same order

Commits are signed off (`git commit -s`) - see CONTRIBUTING.md.
