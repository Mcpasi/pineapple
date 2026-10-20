# R8 / ProGuard rules.
#
# Currently empty on purpose, and that is the point of having the file: this
# app uses no reflection of its own, and its libraries ship their own consumer
# rules — Hilt's generated components, Compose, and Media3 all arrive
# pre-configured. Anything added here should say why it is needed, because an
# unexplained keep rule is a rule nobody dares delete and R8 cannot then trim
# the class it was protecting.
#
# If a release build ever throws `ClassNotFoundException` or a
# `NoSuchMethodError` where the debug build works, that is the signal to add a
# rule here — and to add a test that would have caught it.
