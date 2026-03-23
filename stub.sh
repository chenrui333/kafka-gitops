#!/bin/sh
MYSELF=`which "$0" 2>/dev/null`
[ $? -gt 0 -a -f "$0" ] && MYSELF="./$0"
java=java
if test -n "$JAVA_HOME"; then
    java="$JAVA_HOME/bin/java"
fi

CLASSPATH_PREFIX="$MYSELF"
if test -n "$CLASSPATH"; then
    CLASSPATH_PREFIX="$CLASSPATH:$CLASSPATH_PREFIX"
fi

if test -n "$JAVA_OPTS"; then
    # shellcheck disable=SC2086
    exec "$java" $JAVA_OPTS -cp "$CLASSPATH_PREFIX" com.devshawn.kafka.gitops.MainCommand "$@"
fi

exec "$java" -cp "$CLASSPATH_PREFIX" com.devshawn.kafka.gitops.MainCommand "$@"
exit 1
