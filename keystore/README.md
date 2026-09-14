# keystore

`debug.keystore` is the Android debug key this app is signed with, committed on
purpose. It is not a secret - the password and alias are the standard debug ones
(`android` / `androiddebugkey`) and it only ever signs a sideload build.

It is here because a sideload build has to keep **one** identity. Android
refuses to install an APK over one signed with a different key
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and uninstalling to get past that takes
the app's data with it. Left to itself, every CI run generates a throwaway debug
key of its own, so each build would have been a dead end for the last one.

This is the key the 1.2.0 APK was published with, so builds from CI and from a
clone both install over it in place. **Do not replace it** unless you mean to
break that: a new key means every existing install has to be uninstalled first.